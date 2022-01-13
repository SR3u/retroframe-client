package org.sr3u.retroframe.client;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RetroframeClient {

    public static final int INTEGER_LENGTH = 4;
    private final Logger log;

    public static final Gson GSON = new Gson();
    private final ScheduledExecutorService execService = Executors.newSingleThreadScheduledExecutor();

    private final ImageSizeProvider imageSizeProvider;
    private final OnReceiveHandler onReceive;
    private final RefreshDelayProvider refreshDelayProvider;

    private Socket clientSocket;
    private InputStream in;
    private BufferedWriter out;

    private final int port;
    private final String host;

    public RetroframeClient(Logger log, String host, int port, OnReceiveHandler onReceive, RefreshDelayProvider refreshDelayProvider, ImageSizeProvider imageSizeProvider) {
        this.log = log;
        this.port = port;
        this.host = host;
        this.onReceive = onReceive;
        this.imageSizeProvider = imageSizeProvider;
        this.refreshDelayProvider = refreshDelayProvider;
    }

    public RetroframeClient(Logger log, String host, int port, UltimateImageReceiver ultimateImageReceiver) {
        this(log, host, port, ultimateImageReceiver, ultimateImageReceiver, ultimateImageReceiver);
    }

    public void start() {
        this.updateImageOnce();
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        execService.schedule(this::updateImageAndSchedule, refreshDelayProvider.getRefreshDelay(), TimeUnit.MILLISECONDS);
    }

    private void updateImageAndSchedule() {
        updateImage(true);
    }

    public void updateImageOnce() {
        updateImage(false);
    }

    private synchronized void updateImage(boolean schedule) {
        try {
            try {
                clientSocket = new Socket(host, port);
                in = new BufferedInputStream(clientSocket.getInputStream());
                out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
                out.flush();
                Dimension size = imageSizeProvider.getImageDimension();
                out.write(size.getWidth() + "x" + size.getHeight() + "x" + 32);
                out.write(0);
                out.flush();
                int metadataSize = intFromByteArray(readNBytes(in, INTEGER_LENGTH));
                String json = new String(readNBytes(in, metadataSize));
                Map<String, Object> metaData = parseMetadata(json);
                int imageSize = intFromByteArray(readNBytes(in, INTEGER_LENGTH));
                metaData.put("mem_size", imageSize);
                log.info("Metadata: " + json);
                ImageAndMetadata imageAndMetadata = ImageAndMetadata.builder()
                        .metadataByteSize(metadataSize)
                        .metaData(metaData)
                        .imageByteSize(imageSize)
                        .imageStream(new BufferedInputStream(in))
                        .build();
                onReceive.onReceive(imageAndMetadata);
            } catch (Exception e) {
                log.error(e);
                e.printStackTrace();
            } finally {
                try {
                    if (in != null) {
                        in.close();
                    }
                } catch (Exception e) {
                    log.error(e);
                    e.printStackTrace();
                }
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (Exception e) {
                    log.error(e);
                    e.printStackTrace();
                }
                try {
                    if (clientSocket != null) {
                        clientSocket.close();
                    }
                } catch (Exception e) {
                    log.error(e);
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            log.error(e);
            e.printStackTrace();
        } finally {
            if (schedule) {
                scheduleRefresh();
            }
        }
    }

    private static byte[] readNBytes(InputStream in, int len) throws IOException {
        byte[] buffer = new byte[len];
        int read = in.read(buffer, 0, len);
        return buffer;
    }

    private static byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    private static int intFromByteArray(byte[] bytes) {
        return bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        return GSON.fromJson(json, Map.class);
    }

}
