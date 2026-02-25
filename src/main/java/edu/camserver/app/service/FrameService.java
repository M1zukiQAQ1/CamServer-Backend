package edu.camserver.app.service;

import edu.camserver.app.model.FrameMeta;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;

@Service
public class FrameService {
    private final ReentrantLock lock = new ReentrantLock();
    private byte[] latestFrame;
    private FrameMeta latestMeta = new FrameMeta();
    private String pos;

    public void updateFrame(byte[] data, double clientTs, double serverTs, int latencyMs) {
        lock.lock();
        try {
            this.latestFrame = data;
            this.latestMeta = new FrameMeta(clientTs, serverTs, latencyMs, "%S.%f");
        } finally {
            lock.unlock();
        }
    }

    public void updatePos(String pos) {
        lock.lock();
        try {
            this.pos = pos;
        } finally {
            lock.unlock();
        }
    }

    public byte[] getLatestFrame() {
        lock.lock();
        try { return latestFrame; } finally { lock.unlock(); }
    }

    public FrameMeta getLatestMeta() {
        lock.lock();
        try { return latestMeta; } finally { lock.unlock(); }
    }

    public String getPosMeta() {
        lock.lock();
        try { return pos; } finally { lock.unlock(); }
    }
}

