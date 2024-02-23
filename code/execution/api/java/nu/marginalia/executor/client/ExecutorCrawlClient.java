package nu.marginalia.executor.client;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.functions.execution.api.*;
import nu.marginalia.service.client.GrpcChannelPoolFactory;
import nu.marginalia.service.client.GrpcMultiNodeChannelPool;
import nu.marginalia.service.discovery.property.ServiceKey;
import nu.marginalia.service.discovery.property.ServicePartition;
import nu.marginalia.storage.model.FileStorageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

import static nu.marginalia.functions.execution.api.ExecutorCrawlApiGrpc.*;

@Singleton
public class ExecutorCrawlClient {
    private final GrpcMultiNodeChannelPool<ExecutorCrawlApiBlockingStub> channelPool;
    private static final Logger logger = LoggerFactory.getLogger(ExecutorCrawlClient.class);

    @Inject
    public ExecutorCrawlClient(GrpcChannelPoolFactory grpcChannelPoolFactory)
    {
        this.channelPool = grpcChannelPoolFactory
                .createMulti(
                        ServiceKey.forGrpcApi(ExecutorCrawlApiGrpc.class, ServicePartition.multi()),
                        ExecutorCrawlApiGrpc::newBlockingStub);
    }

    public void triggerCrawl(int node, FileStorageId fid) {
        channelPool.call(ExecutorCrawlApiBlockingStub::triggerCrawl)
                .forNode(node)
                .run(RpcFileStorageId.newBuilder()
                        .setFileStorageId(fid.id())
                        .build());
    }

    public void triggerRecrawl(int node, FileStorageId fid) {
        channelPool.call(ExecutorCrawlApiBlockingStub::triggerRecrawl)
                .forNode(node)
                .run(RpcFileStorageId.newBuilder()
                        .setFileStorageId(fid.id())
                        .build());
    }

    public void triggerConvert(int node, FileStorageId fid) {
        channelPool.call(ExecutorCrawlApiBlockingStub::triggerConvert)
                .forNode(node)
                .run(RpcFileStorageId.newBuilder()
                        .setFileStorageId(fid.id())
                        .build());
    }

    public void triggerConvertAndLoad(int node, FileStorageId fid) {
        channelPool.call(ExecutorCrawlApiBlockingStub::triggerConvertAndLoad)
                .forNode(node)
                .run(RpcFileStorageId.newBuilder()
                        .setFileStorageId(fid.id())
                        .build());
    }

    public void loadProcessedData(int node, List<FileStorageId> ids) {
        channelPool.call(ExecutorCrawlApiBlockingStub::loadProcessedData)
                .forNode(node)
                .run(RpcFileStorageIds.newBuilder()
                        .addAllFileStorageIds(ids.stream().map(FileStorageId::id).toList())
                        .build());
    }

    public void createCrawlSpecFromDownload(int node, String description, String url) {
        channelPool.call(ExecutorCrawlApiBlockingStub::createCrawlSpecFromDownload)
                .forNode(node)
                .run(RpcCrawlSpecFromDownload.newBuilder()
                        .setDescription(description)
                        .setUrl(url)
                        .build());
    }

}
