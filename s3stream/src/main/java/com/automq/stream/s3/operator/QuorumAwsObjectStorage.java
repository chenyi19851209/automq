package com.automq.stream.s3.operator;

import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.tuple.Pair;
import com.automq.stream.s3.metrics.operations.S3Operation;

public class QuorumAwsObjectStorage implements ObjectStorage {
    private final List<AwsObjectStorage> storages;
    private final int quorum;

    public QuorumAwsObjectStorage(List<AwsObjectStorage> storages, int quorum) {
        if (storages == null || storages.isEmpty()) {
            throw new IllegalArgumentException("storages must not be empty");
        }
        if (quorum < 1 || quorum > storages.size()) {
            throw new IllegalArgumentException("quorum must be between 1 and storages.size()");
        }
        this.storages = storages;
        this.quorum = quorum;
    }

    @Override
    public boolean readinessCheck() {
        AtomicInteger success = new AtomicInteger(0);
        for (AwsObjectStorage storage : storages) {
            if (storage.readinessCheck()) {
                if (success.incrementAndGet() >= quorum) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (AwsObjectStorage storage : storages) {
            storage.close();
        }
    }

    // 1. doWrite: 多份写，N份成功
    public CompletableFuture<Void> doWrite(WriteOptions options, String path, ByteBuf data) {
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        CompletableFuture<Void> result = new CompletableFuture<>();
        for (AwsObjectStorage storage : storages) {
            ByteBuf copy = data.retainedDuplicate();
            storage.doWrite(options, path, copy).whenComplete((v, ex) -> {
                try {
                    if (ex == null && success.incrementAndGet() >= quorum) {
                        result.complete(null);
                    }
                } finally {
                    // 确保 ByteBuf 副本被释放
                    if (copy != null && copy.refCnt() > 0) {
                        copy.release();
                    }
                    // 当所有操作都完成时，释放原始 ByteBuf
                    if (completed.incrementAndGet() == storages.size()) {
                        if (data != null && data.refCnt() > 0) {
                            data.release();
                        }
                    }
                }
            });
        }
        return result;
    }

    // 2. doRangeRead: 任意份成功读
    public CompletableFuture<ByteBuf> doRangeRead(ReadOptions options, String path, long start, long end) {
        CompletableFuture<ByteBuf> result = new CompletableFuture<>();
        tryReadRecursive(0, options, path, start, end, result);
        return result;
    }
    private void tryReadRecursive(int idx, ReadOptions options, String path, long start, long end, CompletableFuture<ByteBuf> result) {
        if (idx >= storages.size()) {
            result.completeExceptionally(new RuntimeException("All storages read failed"));
            return;
        }
        storages.get(idx).doRangeRead(options, path, start, end).whenComplete((buf, ex) -> {
            if (ex == null) {
                result.complete(buf);
            } else {
                tryReadRecursive(idx + 1, options, path, start, end, result);
            }
        });
    }

    // 3. doCreateMultipartUpload: 多份写，N份成功
    public CompletableFuture<List<String>> doCreateMultipartUpload(WriteOptions options, String path) {
        AtomicInteger success = new AtomicInteger(0);
        List<String> uploadIds = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        for (AwsObjectStorage storage : storages) {
            storage.doCreateMultipartUpload(options, path).whenComplete((uploadId, ex) -> {
                if (ex == null) {
                    uploadIds.add(uploadId);
                    if (success.incrementAndGet() >= quorum) {
                        result.complete(new ArrayList<>(uploadIds));
                    }
                }
            });
        }
        return result;
    }

    // 4. doUploadPart: 多份写，N份成功，返回quorum份结果
    public CompletableFuture<List<AbstractObjectStorage.ObjectStorageCompletedPart>> doUploadPart(WriteOptions options, String path, List<String> uploadIds, int partNumber, ByteBuf part) {
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);
        List<AbstractObjectStorage.ObjectStorageCompletedPart> parts = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<List<AbstractObjectStorage.ObjectStorageCompletedPart>> result = new CompletableFuture<>();
        for (int i = 0; i < storages.size(); i++) {
            ByteBuf copy = part.retainedDuplicate();
            storages.get(i).doUploadPart(options, path, uploadIds.get(i), partNumber, copy).whenComplete((p, ex) -> {
                try {
                    if (ex == null) {
                        parts.add(p);
                        if (success.incrementAndGet() >= quorum) {
                            result.complete(new ArrayList<>(parts));
                        }
                    }
                } finally {
                    // 确保 ByteBuf 副本被释放
                    if (copy != null && copy.refCnt() > 0) {
                        copy.release();
                    }
                    // 当所有操作都完成时，释放原始 ByteBuf
                    if (completed.incrementAndGet() == storages.size()) {
                        if (part != null && part.refCnt() > 0) {
                            part.release();
                        }
                    }
                }
            });
        }
        return result;
    }

    // 5. doUploadPartCopy: 多份写，N份成功，返回quorum份结果
    public CompletableFuture<List<AbstractObjectStorage.ObjectStorageCompletedPart>> doUploadPartCopy(WriteOptions options, String sourcePath, String path, long start, long end, List<String> uploadIds, int partNumber) {
        AtomicInteger success = new AtomicInteger(0);
        List<AbstractObjectStorage.ObjectStorageCompletedPart> parts = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<List<AbstractObjectStorage.ObjectStorageCompletedPart>> result = new CompletableFuture<>();
        for (int i = 0; i < storages.size(); i++) {
            storages.get(i).doUploadPartCopy(options, sourcePath, path, start, end, uploadIds.get(i), partNumber).whenComplete((p, ex) -> {
                if (ex == null) {
                    parts.add(p);
                    if (success.incrementAndGet() >= quorum) {
                        result.complete(new ArrayList<>(parts));
                    }
                }
            });
        }
        return result;
    }

    // 6. doCompleteMultipartUpload: 多份写，N份成功
    public CompletableFuture<Void> doCompleteMultipartUpload(WriteOptions options, String path, List<String> uploadIds, List<AbstractObjectStorage.ObjectStorageCompletedPart> parts) {
        AtomicInteger success = new AtomicInteger(0);
        CompletableFuture<Void> result = new CompletableFuture<>();
        for (int i = 0; i < storages.size(); i++) {
            storages.get(i).doCompleteMultipartUpload(options, path, uploadIds.get(i), parts).whenComplete((v, ex) -> {
                if (ex == null && success.incrementAndGet() >= quorum) {
                    result.complete(null);
                }
            });
        }
        return result;
    }

    // 7. doDeleteObjects: 多份写，N份成功
    public CompletableFuture<Void> doDeleteObjects(List<String> objectKeys) {
        AtomicInteger success = new AtomicInteger(0);
        CompletableFuture<Void> result = new CompletableFuture<>();
        for (AwsObjectStorage storage : storages) {
            storage.doDeleteObjects(objectKeys).whenComplete((v, ex) -> {
                if (ex == null && success.incrementAndGet() >= quorum) {
                    result.complete(null);
                }
            });
        }
        return result;
    }

    // 8. doList: 任意份成功读
    public CompletableFuture<List<ObjectInfo>> doList(String prefix) {
        CompletableFuture<List<ObjectInfo>> result = new CompletableFuture<>();
        tryListRecursive(0, prefix, result);
        return result;
    }
    private void tryListRecursive(int idx, String prefix, CompletableFuture<List<ObjectInfo>> result) {
        if (idx >= storages.size()) {
            result.completeExceptionally(new RuntimeException("All storages list failed"));
            return;
        }
        storages.get(idx).doList(prefix).whenComplete((list, ex) -> {
            if (ex == null) {
                result.complete(list);
            } else {
                tryListRecursive(idx + 1, prefix, result);
            }
        });
    }

    // 9. toRetryStrategyAndCause: 直接代理第一个storage
    public Pair<RetryStrategy, Throwable> toRetryStrategyAndCause(Throwable ex, S3Operation operation) {
        return storages.get(0).toRetryStrategyAndCause(ex, operation);
    }

    // 兼容ObjectStorage接口的核心方法
    @Override
    public Writer writer(WriteOptions options, String objectPath) {
        // 为每个S3/bucket维护独立的Writer实例
        List<Writer> writers = new ArrayList<>();
        for (AwsObjectStorage storage : storages) {
            writers.add(storage.writer(options, objectPath));
        }
        return new QuorumWriter(writers);
    }

    @Override
    public CompletableFuture<ByteBuf> rangeRead(ReadOptions options, String objectPath, long start, long end) {
        return doRangeRead(options, objectPath, start, end);
    }

    @Override
    public CompletableFuture<List<ObjectInfo>> list(String prefix) {
        return doList(prefix);
    }

    @Override
    public CompletableFuture<Void> delete(List<ObjectPath> objectPaths) {
        List<String> keys = new ArrayList<>();
        for (ObjectPath op : objectPaths) {
            keys.add(op.key());
        }
        return doDeleteObjects(keys);
    }

    @Override
    public short bucketId() {
        return storages.get(0).bucketId();
    }

    // 兼容ObjectStorage接口的write方法
    @Override
    public CompletableFuture<WriteResult> write(WriteOptions options, String objectPath, ByteBuf buf) {
        return doWrite(options, objectPath, buf).thenApply(nil -> new WriteResult(bucketId()));
    }

    class QuorumWriter implements Writer {
        private final List<Writer> writers;
        private final int quorumCount = quorum;

        public QuorumWriter(List<Writer> writers) {
            this.writers = writers;
        }

        @Override
        public CompletableFuture<Void> write(ByteBuf data) {
            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger completed = new AtomicInteger(0);
            CompletableFuture<Void> result = new CompletableFuture<>();

            for (Writer writer : writers) {
                ByteBuf copy = data.retainedDuplicate();
                writer.write(copy).whenComplete((v, ex) -> {
                    try {
                        if (ex == null && success.incrementAndGet() >= quorumCount) {
                            result.complete(null);
                        }
                    } finally {
                        // 确保 ByteBuf 副本被释放
                        if (copy != null && copy.refCnt() > 0) {
                            copy.release();
                        }
                        // 当所有操作都完成时，释放原始 ByteBuf
                        if (completed.incrementAndGet() == writers.size()) {
                            if (data != null && data.refCnt() > 0) {
                                data.release();
                            }
                        }
                    }
                });
            }
            return result;
        }

        @Override
        public void copyOnWrite() {
            for (Writer writer : writers) {
                writer.copyOnWrite();
            }
        }

        @Override
        public void copyWrite(com.automq.stream.s3.metadata.S3ObjectMetadata s3ObjectMetadata, long start, long end) {
            for (Writer writer : writers) {
                writer.copyWrite(s3ObjectMetadata, start, end);
            }
        }

        @Override
        public boolean hasBatchingPart() {
            for (Writer writer : writers) {
                if (writer.hasBatchingPart()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public CompletableFuture<Void> close() {
            AtomicInteger success = new AtomicInteger(0);
            CompletableFuture<Void> result = new CompletableFuture<>();
            for (Writer writer : writers) {
                writer.close().whenComplete((v, ex) -> {
                    if (ex == null && success.incrementAndGet() >= quorumCount) {
                        result.complete(null);
                    }
                });
            }
            return result;
        }

        @Override
        public CompletableFuture<Void> release() {
            List<CompletableFuture<Void>> releases = new ArrayList<>();
            for (Writer writer : writers) {
                releases.add(writer.release());
            }
            return CompletableFuture.allOf(releases.toArray(new CompletableFuture[0]));
        }

        @Override
        public short bucketId() {
            return writers.get(0).bucketId();
        }
    }
}
