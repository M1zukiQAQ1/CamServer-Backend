package edu.camserver.app.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

@Service
public class StreamService {

    private volatile byte[] latestFrame;
    private final ReentrantLock lock = new ReentrantLock();

    // Track connected clients
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        latestFrame = null;
    }

    /**
     * Update the latest frame (called from /upload_live)
     */
    public void setLatestFrame(byte[] frameData) {
        lock.lock();
        try {
            this.latestFrame = frameData;
        } finally {
            lock.unlock();
        }
        broadcastFrame();
    }

    /**
     * Add a client emitter for MJPEG streaming
     */
    public SseEmitter addEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    /**
     * Broadcast the latest frame to all connected clients
     */
    private void broadcastFrame() {
        if (latestFrame == null) return;

        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("frame")
                        .data(latestFrame));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        emitters.removeAll(deadEmitters);
    }
}
