package io.github.xiaoaiswitch;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.xiaoaiswitch.core.ModelClient;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Tracks Breeno 13.4.3 local image attachments, loads them for vision/img2img, and renders generated image cards. */
final class BreenoImageBridge {
    record RenderedImage(String fileUri, int width, int height) {}

    private static final int MAX_DIMENSION = 1600;
    private static final int MAX_RAW_BYTES = 1536 * 1024;
    private static final int JPEG_QUALITY = 85;
    private static final String PLACEHOLDER = "@[绘图结果](breeno_place_holder_0)";

    private final Context context;
    private final ClassLoader loader;
    private final Supplier<String> currentRoom;
    private final Map<String, String> recordPaths = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, List<String>> roomPendingSnapshot = Collections.synchronizedMap(new LinkedHashMap<>());

    BreenoImageBridge(Context context, ClassLoader loader, Supplier<String> currentRoom) {
        this.context = context;
        this.loader = loader;
        this.currentRoom = currentRoom;
    }

    boolean install(Consumer<XC_MethodHook.MethodHookParam> onImageOnlySend) {
        try {
            Class<?> imageSession = XposedHelpers.findClass(
                    "com.heytap.speechassist.aichat.document.session.image.AiChatImageSession", loader);
            Class<?> startDataClass = XposedHelpers.findClass("ll.b", loader);
            imageSession.getDeclaredMethod("I");
            XposedHelpers.findAndHookMethod(imageSession, "S", startDataClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        rememberFromSession(param.thisObject, param.args[0]);
                    } catch (Throwable error) {
                        XposedBridge.log("XiaoBuSwitch: image session capture " + error.getClass().getSimpleName());
                    }
                }
            });

            Class<?> pendingModule = XposedHelpers.findClass(
                    "com.heytap.speechassist.aichathome.chat.ui.input.modules.AIChatHomePendingImageModule", loader);
            Class<?> function3 = XposedHelpers.findClass("kotlin.jvm.functions.Function3", loader);
            pendingModule.getDeclaredMethod("E");
            XposedHelpers.findAndHookMethod(pendingModule, "f0", function3, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object handle = XposedHelpers.callMethod(param.thisObject, "E");
                        String roomId = currentRoom.get();
                        if (handle != null && roomId != null && !roomId.isEmpty()) {
                            List<String> sources = captureHandleImages(handle, roomId);
                            if (!sources.isEmpty()) {
                                rememberRoomSnapshot(roomId, sources);
                            }
                        }
                    } catch (Throwable error) {
                        XposedBridge.log("XiaoBuSwitch: pending image capture " + error.getClass().getSimpleName());
                    }
                }
            });

            Class<?> imageHandle = XposedHelpers.findClass(
                    "com.heytap.speechassist.aichat.document.multimodal.handle.AiChatModalNewCuiOcrImageHandle", loader);
            imageHandle.getDeclaredMethod("o", String.class);
            imageHandle.getDeclaredMethod("F", String.class);
            XposedHelpers.findAndHookMethod(imageHandle, "H", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (onImageOnlySend != null) {
                        onImageOnlySend.accept(param);
                    }
                }
            });
            return true;
        } catch (Throwable error) {
            XposedBridge.log("XiaoBuSwitch: image bridge install failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    List<String> captureHandleImages(Object handle, String roomId) {
        List<String> sources = new ArrayList<>();
        if (handle == null || roomId == null || roomId.isEmpty()) return sources;
        Object rawList = XposedHelpers.callMethod(handle, "o", roomId);
        if (!(rawList instanceof List<?> items)) return sources;
        for (Object item : items) {
            if (item == null) continue;
            String source = extractLocalSource(item);
            Object docEntity = XposedHelpers.callMethod(item, "getDocEntity");
            Object uploadData = docEntity == null ? null : XposedHelpers.callMethod(docEntity, "getData");
            if (source != null && !source.isEmpty()) {
                sources.add(source);
                rememberUploadData(uploadData, source);
            }
        }
        return sources;
    }

    List<String> resolveSources(String roomId, List<String> recordIds) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (recordIds != null) {
            for (String id : recordIds) {
                if (id == null || id.isEmpty()) continue;
                String path = recordPaths.get(id);
                if (path != null && !path.isEmpty()) {
                    resolved.add(path);
                }
            }
        }
        if (resolved.isEmpty() && roomId != null && !roomId.isEmpty()) {
            List<String> snapshot = roomPendingSnapshot.get(roomId);
            if (snapshot != null) {
                resolved.addAll(snapshot);
            }
        }
        return new ArrayList<>(resolved);
    }

    void clearRoomSnapshot(String roomId) {
        if (roomId != null && !roomId.isEmpty()) {
            roomPendingSnapshot.remove(roomId);
        }
    }

    List<ModelClient.Image> loadImages(List<String> sources, ModelClient.Cancellation cancellation) throws Exception {
        List<ModelClient.Image> result = new ArrayList<>();
        for (String source : sources) {
            if (source == null || source.isEmpty()) continue;
            result.add(loadSource(source, cancellation));
        }
        return result;
    }

    ModelClient.Image loadSource(String source, ModelClient.Cancellation cancellation) throws Exception {
        byte[] bytes;
        if (source.startsWith("data:image")) {
            bytes = Base64.decode(source.substring(source.indexOf(',') + 1), Base64.DEFAULT);
        } else if (source.startsWith("https://") || source.startsWith("http://")) {
            bytes = ModelClient.downloadImage(source, cancellation);
        } else {
            try (InputStream input = source.startsWith("content://")
                    ? context.getContentResolver().openInputStream(Uri.parse(source))
                    : new FileInputStream(source.startsWith("file://") ? Uri.parse(source).getPath() : source)) {
                if (input == null) throw new IllegalStateException("无法打开图片附件");
                bytes = ModelClient.read(input, cancellation);
            }
        }
        if (bytes.length == 0) throw new IllegalArgumentException("图片附件为空");
        return normalizeImage(bytes);
    }

    static String dataUrl(ModelClient.Image image) {
        return "data:" + image.mime() + ";base64," + Base64.encodeToString(image.bytes(), Base64.NO_WRAP);
    }

    static String appendImagePlaceholder(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? PLACEHOLDER : trimmed + "\n\n" + PLACEHOLDER;
    }

    List<RenderedImage> materializeGenerated(List<String> sources, ModelClient.Cancellation cancellation) throws Exception {
        File dir = new File(context.getCacheDir(), "xiaobu_images");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建绘图缓存目录");
        }
        pruneCache(dir);
        List<RenderedImage> rendered = new ArrayList<>();
        for (String source : sources) {
            if (source == null || source.isEmpty()) continue;
            byte[] bytes = readRawBytes(source, cancellation);
            if (bytes.length == 0) continue;
            String mime = detectMime(bytes);
            String ext = "image/jpeg".equals(mime) ? ".jpg" : "image/webp".equals(mime) ? ".webp" : ".png";
            File file = new File(dir, "gen_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ext);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int width = bounds.outWidth > 0 ? bounds.outWidth : 1024;
            int height = bounds.outHeight > 0 ? bounds.outHeight : 1024;
            rendered.add(new RenderedImage(Uri.fromFile(file).toString(), width, height));
        }
        return rendered;
    }

    boolean attachMarkdownImages(Object answerBean, List<RenderedImage> images) {
        if (answerBean == null || images == null || images.isEmpty()) return false;
        try {
            Class<?> cardInfoClass = XposedHelpers.findClass(
                    "com.heytap.speech.engine.protocol.directive.myai.CardInfo", loader);
            Class<?> imageInfosClass = XposedHelpers.findClass(
                    "com.heytap.speech.engine.protocol.directive.myai.ImageInfos", loader);
            Class<?> imageInfoClass = XposedHelpers.findClass(
                    "com.heytap.speech.engine.protocol.directive.myai.ImageInfo", loader);

            ArrayList<Object> infoList = new ArrayList<>(images.size());
            for (RenderedImage img : images) {
                Object info = imageInfoClass.getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(info, "setIndex", 0);
                XposedHelpers.callMethod(info, "setUrl", img.fileUri());
                XposedHelpers.callMethod(info, "setWidth", String.valueOf(img.width()));
                XposedHelpers.callMethod(info, "setHeight", String.valueOf(img.height()));
                XposedHelpers.callMethod(info, "setSecurityCheckResult", 1);
                XposedHelpers.callMethod(info, "setType", "image");
                XposedHelpers.callMethod(info, "setTitle", "绘图结果");
                infoList.add(info);
            }
            Object imageInfos = imageInfosClass.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(imageInfos, "setImages", infoList);

            Object cardInfo = cardInfoClass.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(cardInfo, "setIndex", 0);
            XposedHelpers.callMethod(cardInfo, "setType", "image");
            XposedHelpers.callMethod(cardInfo, "setInfo", imageInfos);

            ArrayList<Object> cards = new ArrayList<>(1);
            cards.add(cardInfo);
            XposedHelpers.callMethod(answerBean, "setMarkdownCardInfos", cards);
            return true;
        } catch (Throwable error) {
            XposedBridge.log("XiaoBuSwitch: attachMarkdownImages failed " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private byte[] readRawBytes(String source, ModelClient.Cancellation cancellation) throws Exception {
        if (source.startsWith("data:image")) {
            return Base64.decode(source.substring(source.indexOf(',') + 1), Base64.DEFAULT);
        }
        if (source.startsWith("https://") || source.startsWith("http://")) {
            return ModelClient.downloadImage(source, cancellation);
        }
        try (InputStream input = source.startsWith("content://")
                ? context.getContentResolver().openInputStream(Uri.parse(source))
                : new FileInputStream(source.startsWith("file://") ? Uri.parse(source).getPath() : source)) {
            if (input == null) throw new IllegalStateException("无法读取图片文件");
            return ModelClient.read(input, cancellation);
        }
    }

    private static void pruneCache(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 32) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            // Best-effort cache cleanup
            files[i].delete();
        }
    }

    private ModelClient.Image normalizeImage(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        int maxSide = Math.max(width, height);
        String mime = detectMime(bytes);
        if (mime != null && bytes.length <= MAX_RAW_BYTES && maxSide > 0 && maxSide <= MAX_DIMENSION) {
            return new ModelClient.Image(bytes, mime);
        }
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        if (maxSide > MAX_DIMENSION) {
            int sample = 1;
            while (maxSide / (sample * 2) >= MAX_DIMENSION) {
                sample *= 2;
            }
            decodeOptions.inSampleSize = sample;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decodeOptions);
        if (bitmap == null) {
            if (mime != null) return new ModelClient.Image(bytes, mime);
            throw new IllegalArgumentException("原图格式不受支持");
        }
        Bitmap scaled = bitmap;
        try {
            int currentMax = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (currentMax > MAX_DIMENSION) {
                float scale = (float) MAX_DIMENSION / currentMax;
                int targetW = Math.max(1, Math.round(bitmap.getWidth() * scale));
                int targetH = Math.max(1, Math.round(bitmap.getHeight() * scale));
                scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output);
                return new ModelClient.Image(output.toByteArray(), "image/jpeg");
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("图片编码失败", error);
        } finally {
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
        }
    }

    private static String detectMime(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private void rememberFromSession(Object session, Object startData) {
        if (session == null || startData == null) return;
        Object imageData = XposedHelpers.callMethod(session, "I");
        String source = imageData == null ? null : extractLocalSource(imageData);
        if (source == null || source.isEmpty()) {
            Object uri = XposedHelpers.callMethod(startData, "w");
            if (uri != null) source = uri.toString();
        }
        if (source == null || source.isEmpty()) return;
        Object uploadData = XposedHelpers.callMethod(startData, "u");
        rememberUploadData(uploadData, source);
    }

    private void rememberUploadData(Object uploadData, String source) {
        if (uploadData == null || source == null || source.isEmpty()) return;
        Object recordId = XposedHelpers.callMethod(uploadData, "getRecordId");
        if (recordId instanceof String id && !id.isEmpty()) {
            remember(id, source);
        }
        Object docRecordId = XposedHelpers.callMethod(uploadData, "getDocRecordId");
        if (docRecordId instanceof String id && !id.isEmpty()) {
            remember(id, source);
        }
    }

    private String extractLocalSource(Object imageData) {
        Object uri = XposedHelpers.callMethod(imageData, "getUri");
        if (uri != null && !uri.toString().isEmpty()) return uri.toString();
        Object originUri = XposedHelpers.callMethod(imageData, "getOriginUri");
        if (originUri != null && !originUri.toString().isEmpty()) return originUri.toString();
        Object docEntity = XposedHelpers.callMethod(imageData, "getDocEntity");
        if (docEntity != null) {
            Object filePath = XposedHelpers.callMethod(docEntity, "getFilePath");
            if (filePath instanceof String path && !path.isEmpty()) return path;
            Object originFilePath = XposedHelpers.callMethod(docEntity, "getOriginFilePath");
            if (originFilePath instanceof String path && !path.isEmpty()) return path;
            Object mediaUri = XposedHelpers.callMethod(docEntity, "getImageMediaUriForChatQueryMsg");
            if (mediaUri instanceof String path && !path.isEmpty()) return path;
        }
        return null;
    }

    private void remember(String id, String source) {
        synchronized (recordPaths) {
            recordPaths.put(id, source);
            while (recordPaths.size() > 64) {
                recordPaths.remove(recordPaths.keySet().iterator().next());
            }
        }
    }

    private void rememberRoomSnapshot(String roomId, List<String> sources) {
        synchronized (roomPendingSnapshot) {
            roomPendingSnapshot.put(roomId, new ArrayList<>(sources));
            while (roomPendingSnapshot.size() > 8) {
                roomPendingSnapshot.remove(roomPendingSnapshot.keySet().iterator().next());
            }
        }
    }
}
