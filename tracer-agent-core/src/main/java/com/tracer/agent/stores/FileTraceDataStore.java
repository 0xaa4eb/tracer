package com.tracer.agent.stores;

import com.tracer.agent.*;
import com.tracer.agent.tracers.AvgCallTimeTracer;
import com.tracer.agent.util.LoggingSettings;
import com.tracer.agent.util.NamedThreadFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MpscUnboundedArrayQueue;

@Slf4j
public class FileTraceDataStore implements TraceDataStore {

    private static final int QUEUES_COUNT = 16;
    private static final int QUEUES_IDX_MASK = QUEUES_COUNT - 1;
    private static final int CHUNK_SIZE = 1024;

    private final MpscUnboundedArrayQueue<TraceRecord>[] queues = new MpscUnboundedArrayQueue[QUEUES_COUNT];

    private final ScheduledExecutorService scheduledExecutorService;
    private final MethodRepository methodRepository;

    public FileTraceDataStore(Settings settings, MethodRepository methodRepository) throws IOException {
        this.methodRepository = methodRepository;

        for (int i = 0; i < QUEUES_COUNT; i++) {
            queues[i] = new MpscUnboundedArrayQueue<>(CHUNK_SIZE);
        }

        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(NamedThreadFactory.builder().name("tracer-stdout").daemon(true).build());
        scheduledExecutorService.scheduleAtFixedRate(
                new QueueScanner(new File(settings.getFile())),
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    public void store(TraceRecord record) {
        int queueIdx = (int) Thread.currentThread().getId() & QUEUES_IDX_MASK;
        queues[queueIdx].add(record);
        if (LoggingSettings.TRACE_ENABLED) {
            log.trace("Stored record {}", record);
        }
    }

    private class QueueScanner implements Runnable {

        private final BufferedWriter writer;
        private final Map<Integer, AvgCallTimeTracer> tracers = new ConcurrentHashMap<>();

        private QueueScanner(File file) throws IOException {
            writer = new BufferedWriter(new FileWriter(file, true));
        }

        @Override
        public void run() {
            try {
                flushQueues();
                writeTracerReports();
            } catch (IOException e) {
                log.error("Failed to write profile data", e);
                throw new RuntimeException(e);
            }
        }

        private void writeTracerReports() throws IOException {
            for (Map.Entry<Integer, AvgCallTimeTracer> entry : tracers.entrySet()) {
                if (!entry.getValue().hasSomething()) {
                    continue;
                }
                Integer methodId = entry.getKey();
                Method method = methodRepository.get(methodId);
                AvgCallTimeTracer tracer = entry.getValue();
                writer.write(LocalDateTime.now() + " method: " + method.toShortString() + tracer.report());
                writer.newLine();
                tracer.reset();
                writer.flush();
            }
        }

        private void flushQueues() {
            for (int qIdx = 0; qIdx < QUEUES_COUNT; qIdx++) {
                flushQueue(qIdx);
            }
        }

        private void flushQueue(int queueIdx) {
            MpscUnboundedArrayQueue<TraceRecord> queue = queues[queueIdx];

            while (!queue.isEmpty()) {

                TraceRecord traceRecord = queue.relaxedPoll();

                if (traceRecord instanceof MethodCallTimeRecord) {
                    MethodCallTimeRecord callTimeRecord = (MethodCallTimeRecord) traceRecord;
                    AvgCallTimeTracer tracer = tracers.get(traceRecord.getMethodId());
                    if (tracer != null) {
                        tracer.record(callTimeRecord.getNanos());
                    } else {
                        tracer = new AvgCallTimeTracer();
                        tracers.put(traceRecord.getMethodId(), tracer);
                        tracer.record(callTimeRecord.getNanos());
                    }
                    if (LoggingSettings.TRACE_ENABLED) {
                        log.trace("Processed record {}", traceRecord);
                    }
                }
            }
        }
    }
}
