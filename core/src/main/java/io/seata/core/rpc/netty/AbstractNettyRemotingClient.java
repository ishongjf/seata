/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.core.rpc.netty;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.EventExecutorGroup;
import io.seata.common.exception.FrameworkErrorCode;
import io.seata.common.exception.FrameworkException;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.common.util.CollectionUtils;
import io.seata.common.util.NetUtil;
import io.seata.common.util.StringUtils;
import io.seata.core.protocol.AbstractMessage;
import io.seata.core.protocol.HeartbeatMessage;
import io.seata.core.protocol.MergeMessage;
import io.seata.core.protocol.MergedWarpMessage;
import io.seata.core.protocol.MessageFuture;
import io.seata.core.protocol.ProtocolConstants;
import io.seata.core.protocol.RpcMessage;
import io.seata.core.protocol.transaction.AbstractGlobalEndRequest;
import io.seata.core.protocol.transaction.BranchRegisterRequest;
import io.seata.core.protocol.transaction.BranchReportRequest;
import io.seata.core.protocol.transaction.GlobalBeginRequest;
import io.seata.core.rpc.RemotingClient;
import io.seata.core.rpc.TransactionMessageHandler;
import io.seata.core.rpc.processor.Pair;
import io.seata.core.rpc.processor.RemotingProcessor;
import io.seata.discovery.loadbalance.LoadBalanceFactory;
import io.seata.discovery.registry.RegistryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.seata.common.exception.FrameworkErrorCode.NoAvailableService;

/**
 * The netty remoting client.
 *
 * @author slievrly
 * @author zhaojun
 * @author zhangchenghui.dev@gmail.com
 */
public abstract class AbstractNettyRemotingClient extends AbstractNettyRemoting implements RemotingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractNettyRemotingClient.class);
    private static final String MSG_ID_PREFIX = "msgId:";
    private static final String FUTURES_PREFIX = "futures:";
    private static final String SINGLE_LOG_POSTFIX = ";";
    private static final int MAX_MERGE_SEND_MILLS = 1;
    private static final String THREAD_PREFIX_SPLIT_CHAR = "_";

    private static final int MAX_MERGE_SEND_THREAD = 1;
    private static final long KEEP_ALIVE_TIME = Integer.MAX_VALUE;
    private static final long SCHEDULE_DELAY_MILLS = 60 * 1000L;
    private static final long SCHEDULE_INTERVAL_MILLS = 10 * 1000L;
    private static final String MERGE_THREAD_PREFIX = "rpcMergeMessageSend";
    protected final Object mergeLock = new Object();

    /**
     * When sending message type is {@link MergeMessage}, will be stored to mergeMsgMap.
     */
    protected final Map<Integer, MergeMessage> mergeMsgMap = new ConcurrentHashMap<>();

    /**
     * When batch sending is enabled, the message will be stored to basketMap
     * Send via asynchronous thread {@link MergedSendRunnable}
     * {@link NettyClientConfig#isEnableClientBatchSendRequest}
     */
    protected final ConcurrentHashMap<String/*serverAddress*/, BlockingQueue<RpcMessage>> basketMap = new ConcurrentHashMap<>();

    private final NettyClientBootstrap clientBootstrap;
    private NettyClientChannelManager clientChannelManager;
    private final NettyPoolKey.TransactionRole transactionRole;
    private ExecutorService mergeSendExecutorService;
    private TransactionMessageHandler transactionMessageHandler;

    @Override
    public void init() {
        //创建一个每10秒执行一次的定时任务，用于处理Channel的保活
        timerExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                clientChannelManager.reconnect(getTransactionServiceGroup());
            }
        }, SCHEDULE_DELAY_MILLS, SCHEDULE_INTERVAL_MILLS, TimeUnit.MILLISECONDS);
        //判断消息是否批量发送，是则创建一个线程去批量发送消息
        if (NettyClientConfig.isEnableClientBatchSendRequest()) {
            mergeSendExecutorService = new ThreadPoolExecutor(MAX_MERGE_SEND_THREAD,
                MAX_MERGE_SEND_THREAD,
                KEEP_ALIVE_TIME, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new NamedThreadFactory(getThreadPrefix(), MAX_MERGE_SEND_THREAD));
            mergeSendExecutorService.submit(new MergedSendRunnable());
        }
        //调用父类的init，创建了一个定时任务
        super.init();
        //配置Bootstrap
        clientBootstrap.start();
    }

    public AbstractNettyRemotingClient(NettyClientConfig nettyClientConfig, EventExecutorGroup eventExecutorGroup,
                                       ThreadPoolExecutor messageExecutor, NettyPoolKey.TransactionRole transactionRole) {
        super(messageExecutor);
        this.transactionRole = transactionRole;
        //创建NettyClientBootstrap，NettyClientBootstrap内有Bootstrap，用于跟server连接
        clientBootstrap = new NettyClientBootstrap(nettyClientConfig, eventExecutorGroup, transactionRole);
        //添加ClientHandler，用于处理消息返回
        clientBootstrap.setChannelHandlers(new ClientHandler());
        //NettyClientChannelManager是用于管理Channel连接池，通过NettyPoolableFactory#makeObject创建Channel
        //getPoolKeyFunction()返回一个NettyPoolKey，包装了服务器地址和注册消息
        clientChannelManager = new NettyClientChannelManager(
            new NettyPoolableFactory(this, clientBootstrap), getPoolKeyFunction(), nettyClientConfig);
    }

    @Override
    public Object sendSyncRequest(Object msg) throws TimeoutException {
        //根据serviceGroup获取服务地址
        String serverAddress = loadBalance(getTransactionServiceGroup(), msg);
        int timeoutMillis = NettyClientConfig.getRpcRequestTimeout();
        //将消息内容封装为一个RpcMessage
        RpcMessage rpcMessage = buildRequestMessage(msg, ProtocolConstants.MSGTYPE_RESQUEST_SYNC);

        // send batch message
        // put message into basketMap, @see MergedSendRunnable
        //判断消息是否是批量发送
        if (NettyClientConfig.isEnableClientBatchSendRequest()) {

            // send batch message is sync request, needs to create messageFuture and put it in futures.
            //创建一个MessageFuture用于异步返回消息
            MessageFuture messageFuture = new MessageFuture();
            messageFuture.setRequestMessage(rpcMessage);
            messageFuture.setTimeout(timeoutMillis);
            futures.put(rpcMessage.getId(), messageFuture);

            // put message into basketMap
            //根据服务地址获取到消息队列
            BlockingQueue<RpcMessage> basket = CollectionUtils.computeIfAbsent(basketMap, serverAddress,
                key -> new LinkedBlockingQueue<>());
            //将消息放入消息队列
            if (!basket.offer(rpcMessage)) {
                LOGGER.error("put message into basketMap offer failed, serverAddress:{},rpcMessage:{}",
                        serverAddress, rpcMessage);
                return null;
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("offer message: {}", rpcMessage.getBody());
            }
            //isSending表示消息是否正在发送，false时唤醒批量发送的等待线程
            if (!isSending) {
                synchronized (mergeLock) {
                    mergeLock.notifyAll();
                }
            }

            try {
                return messageFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (Exception exx) {
                LOGGER.error("wait response error:{},ip:{},request:{}",
                    exx.getMessage(), serverAddress, rpcMessage.getBody());
                if (exx instanceof TimeoutException) {
                    throw (TimeoutException) exx;
                } else {
                    throw new RuntimeException(exx);
                }
            }

        } else {
            Channel channel = clientChannelManager.acquireChannel(serverAddress);
            return super.sendSync(channel, rpcMessage, timeoutMillis);
        }

    }

    @Override
    public Object sendSyncRequest(Channel channel, Object msg) throws TimeoutException {
        if (channel == null) {
            LOGGER.warn("sendSyncRequest nothing, caused by null channel.");
            return null;
        }
        RpcMessage rpcMessage = buildRequestMessage(msg, ProtocolConstants.MSGTYPE_RESQUEST_SYNC);
        return super.sendSync(channel, rpcMessage, NettyClientConfig.getRpcRequestTimeout());
    }

    @Override
    public void sendAsyncRequest(Channel channel, Object msg) {
        if (channel == null) {
            LOGGER.warn("sendAsyncRequest nothing, caused by null channel.");
            return;
        }
        RpcMessage rpcMessage = buildRequestMessage(msg, msg instanceof HeartbeatMessage
            ? ProtocolConstants.MSGTYPE_HEARTBEAT_REQUEST
            : ProtocolConstants.MSGTYPE_RESQUEST_ONEWAY);
        if (rpcMessage.getBody() instanceof MergeMessage) {
            //将消息放入mergeMsgMap
            mergeMsgMap.put(rpcMessage.getId(), (MergeMessage) rpcMessage.getBody());
        }
        super.sendAsync(channel, rpcMessage);
    }

    @Override
    public void sendAsyncResponse(String serverAddress, RpcMessage rpcMessage, Object msg) {
        RpcMessage rpcMsg = buildResponseMessage(rpcMessage, msg, ProtocolConstants.MSGTYPE_RESPONSE);
        Channel channel = clientChannelManager.acquireChannel(serverAddress);
        super.sendAsync(channel, rpcMsg);
    }

    @Override
    public void registerProcessor(int requestCode, RemotingProcessor processor, ExecutorService executor) {
        Pair<RemotingProcessor, ExecutorService> pair = new Pair<>(processor, executor);
        this.processorTable.put(requestCode, pair);
    }

    @Override
    public void destroyChannel(String serverAddress, Channel channel) {
        clientChannelManager.destroyChannel(serverAddress, channel);
    }

    @Override
    public void destroy() {
        clientBootstrap.shutdown();
        if (mergeSendExecutorService != null) {
            mergeSendExecutorService.shutdown();
        }
        super.destroy();
    }

    public void setTransactionMessageHandler(TransactionMessageHandler transactionMessageHandler) {
        this.transactionMessageHandler = transactionMessageHandler;
    }

    public TransactionMessageHandler getTransactionMessageHandler() {
        return transactionMessageHandler;
    }

    public NettyClientChannelManager getClientChannelManager() {
        return clientChannelManager;
    }

    private String loadBalance(String transactionServiceGroup, Object msg) {
        InetSocketAddress address = null;
        try {
            @SuppressWarnings("unchecked")
            List<InetSocketAddress> inetSocketAddressList = RegistryFactory.getInstance().lookup(transactionServiceGroup);
            address = LoadBalanceFactory.getInstance().select(inetSocketAddressList, getXid(msg));
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
        if (address == null) {
            throw new FrameworkException(NoAvailableService);
        }
        return NetUtil.toStringAddress(address);
    }

    private String getXid(Object msg) {
        String xid = "";
        if (msg instanceof AbstractGlobalEndRequest) {
            xid = ((AbstractGlobalEndRequest) msg).getXid();
        } else if (msg instanceof GlobalBeginRequest) {
            xid = ((GlobalBeginRequest) msg).getTransactionName();
        } else if (msg instanceof BranchRegisterRequest) {
            xid = ((BranchRegisterRequest) msg).getXid();
        } else if (msg instanceof BranchReportRequest) {
            xid = ((BranchReportRequest) msg).getXid();
        } else {
            try {
                Field field = msg.getClass().getDeclaredField("xid");
                xid = String.valueOf(field.get(msg));
            } catch (Exception ignore) {
            }
        }
        return StringUtils.isBlank(xid) ? String.valueOf(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)) : xid;
    }

    private String getThreadPrefix() {
        return AbstractNettyRemotingClient.MERGE_THREAD_PREFIX + THREAD_PREFIX_SPLIT_CHAR + transactionRole.name();
    }

    /**
     * Get pool key function.
     *
     * @return lambda function
     */
    protected abstract Function<String, NettyPoolKey> getPoolKeyFunction();

    /**
     * Get transaction service group.
     *
     * @return transaction service group
     */
    protected abstract String getTransactionServiceGroup();

    /**
     * The type Merged send runnable.
     */
    private class MergedSendRunnable implements Runnable {

        @Override
        public void run() {
            while (true) {
                synchronized (mergeLock) {
                    try {
                        //等待消息来临时被唤醒
                        mergeLock.wait(MAX_MERGE_SEND_MILLS);
                    } catch (InterruptedException e) {
                    }
                }
                //标记消息开始发送
                isSending = true;
                basketMap.forEach((address, basket) -> {
                    if (basket.isEmpty()) {
                        return;
                    }
                    //创建一个MergedWarpMessage，将同一个地址的消息放进去
                    MergedWarpMessage mergeMessage = new MergedWarpMessage();
                    while (!basket.isEmpty()) {
                        RpcMessage msg = basket.poll();
                        mergeMessage.msgs.add((AbstractMessage) msg.getBody());
                        mergeMessage.msgIds.add(msg.getId());
                    }
                    if (mergeMessage.msgIds.size() > 1) {
                        printMergeMessageLog(mergeMessage);
                    }
                    Channel sendChannel = null;
                    try {
                        // send batch message is sync request, but there is no need to get the return value.
                        // Since the messageFuture has been created before the message is placed in basketMap,
                        // the return value will be obtained in ClientOnResponseProcessor.
                        //获取通道发送消息
                        sendChannel = clientChannelManager.acquireChannel(address);
                        AbstractNettyRemotingClient.this.sendAsyncRequest(sendChannel, mergeMessage);
                    } catch (FrameworkException e) {
                        if (e.getErrcode() == FrameworkErrorCode.ChannelIsNotWritable && sendChannel != null) {
                            destroyChannel(address, sendChannel);
                        }
                        // fast fail
                        //发送消息失败时，将消息从futures中移出且设置返回值为null
                        for (Integer msgId : mergeMessage.msgIds) {
                            MessageFuture messageFuture = futures.remove(msgId);
                            if (messageFuture != null) {
                                messageFuture.setResultMessage(null);
                            }
                        }
                        LOGGER.error("client merge call failed: {}", e.getMessage(), e);
                    }
                });
                //消息发送完毕，发送状态标记为false
                isSending = false;
            }
        }

        private void printMergeMessageLog(MergedWarpMessage mergeMessage) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("merge msg size:{}", mergeMessage.msgIds.size());
                for (AbstractMessage cm : mergeMessage.msgs) {
                    LOGGER.debug(cm.toString());
                }
                StringBuilder sb = new StringBuilder();
                for (long l : mergeMessage.msgIds) {
                    sb.append(MSG_ID_PREFIX).append(l).append(SINGLE_LOG_POSTFIX);
                }
                sb.append("\n");
                for (long l : futures.keySet()) {
                    sb.append(FUTURES_PREFIX).append(l).append(SINGLE_LOG_POSTFIX);
                }
                LOGGER.debug(sb.toString());
            }
        }
    }

    /**
     * The type ClientHandler.
     */
    @Sharable
    class ClientHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof RpcMessage)) {
                return;
            }
            //处理接收到的消息
            processMessage(ctx, (RpcMessage) msg);
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            synchronized (lock) {
                if (ctx.channel().isWritable()) {
                    lock.notifyAll();
                }
            }
            ctx.fireChannelWritabilityChanged();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (messageExecutor.isShutdown()) {
                return;
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("channel inactive: {}", ctx.channel());
            }
            clientChannelManager.releaseChannel(ctx.channel(), NetUtil.toStringAddress(ctx.channel().remoteAddress()));
            super.channelInactive(ctx);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent idleStateEvent = (IdleStateEvent) evt;
                if (idleStateEvent.state() == IdleState.READER_IDLE) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("channel {} read idle.", ctx.channel());
                    }
                    try {
                        String serverAddress = NetUtil.toStringAddress(ctx.channel().remoteAddress());
                        clientChannelManager.invalidateObject(serverAddress, ctx.channel());
                    } catch (Exception exx) {
                        LOGGER.error(exx.getMessage());
                    } finally {
                        clientChannelManager.releaseChannel(ctx.channel(), getAddressFromContext(ctx));
                    }
                }
                if (idleStateEvent == IdleStateEvent.WRITER_IDLE_STATE_EVENT) {
                    try {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("will send ping msg,channel {}", ctx.channel());
                        }
                        AbstractNettyRemotingClient.this.sendAsyncRequest(ctx.channel(), HeartbeatMessage.PING);
                    } catch (Throwable throwable) {
                        LOGGER.error("send request error: {}", throwable.getMessage(), throwable);
                    }
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            LOGGER.error(FrameworkErrorCode.ExceptionCaught.getErrCode(),
                NetUtil.toStringAddress(ctx.channel().remoteAddress()) + "connect exception. " + cause.getMessage(), cause);
            clientChannelManager.releaseChannel(ctx.channel(), getAddressFromChannel(ctx.channel()));
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("remove exception rm channel:{}", ctx.channel());
            }
            super.exceptionCaught(ctx, cause);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise future) throws Exception {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(ctx + " will closed");
            }
            super.close(ctx, future);
        }
    }
}
