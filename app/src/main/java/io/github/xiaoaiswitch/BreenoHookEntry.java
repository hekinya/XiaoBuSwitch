package io.github.xiaoaiswitch;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.xiaoaiswitch.core.ChatProtocol;
import io.github.xiaoaiswitch.core.CodexClient;
import io.github.xiaoaiswitch.core.CodexOAuth;
import io.github.xiaoaiswitch.core.DialogueLoop;
import io.github.xiaoaiswitch.core.DialogueProtocol;
import io.github.xiaoaiswitch.core.ImageProtocol;
import io.github.xiaoaiswitch.core.ModeProfiles;
import io.github.xiaoaiswitch.core.ModelClient;
import io.github.xiaoaiswitch.core.Profile;
import io.github.xiaoaiswitch.core.QueryRouter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Hook for the ColorOS Breeno build supplied with this port. */
public final class BreenoHookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.heytap.speechassist";
    private static final int TARGET_VERSION = 130403;
    private static final String RECORD_ID_KEY = "customized_record_id";
    private static final String STATE_MANAGER_COMPANION_FIELD = "g";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!TARGET_PACKAGE.equals(load.packageName) || !TARGET_PACKAGE.equals(load.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!INSTALLED.compareAndSet(false, true)) return;
                Context base = (Context) param.args[0];
                Context context = base == null ? null : (base.getApplicationContext() != null ? base.getApplicationContext() : base);
                if (context == null && param.thisObject instanceof Context) {
                    context = (Context) param.thisObject;
                }
                if (context == null) {
                    XposedBridge.log("XiaoBuSwitch: install null context");
                    return;
                }
                try {
                    PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
                    if (info.versionCode != TARGET_VERSION) {
                        report(context, "小布版本不匹配：检测到 " + info.versionName + "（" + info.versionCode
                                + "），当前适配 13.4.3（130403）；保留原厂链路", false);
                        return;
                    }
                    Class<?> helper = XposedHelpers.findClassIfExists(
                            "com.heytap.speechassist.aichat.floatwindow.AIChatEngineHelper", load.classLoader);
                    if (helper == null) {
                        report(context, "未找到小布对话组件；保留原厂链路", false);
                        return;
                    }
                    Method submit = helper.getDeclaredMethod("y", String.class, int.class, Bundle.class,
                            boolean.class, boolean.class);
                    if (submit.getReturnType() != Void.TYPE) {
                        report(context, "小布文本接口结构不匹配；保留原厂链路", false);
                        return;
                    }
                    validateRuntimeShape(helper, load.classLoader);
                    boolean visionReady = new Runtime(context, load.classLoader).install(helper);
                    report(context, visionReady
                            ? "已加载小布 13.4.3 文本、识图与绘图 Hook；请在小布 Switch 中配置模型"
                            : "已加载小布 13.4.3 文本与绘图 Hook（识图附件未就绪）；请在小布 Switch 中配置模型", true);
                } catch (Throwable error) {
                    report(context, "小布 Hook 安装失败：" + error.getClass().getSimpleName(), false);
                    XposedBridge.log("XiaoBuSwitch: install " + error);
                }
            }
        });
    }

    private static void validateRuntimeShape(Class<?> helper, ClassLoader loader) throws Exception {
        Class<?> answer = XposedHelpers.findClass(
                "com.heytap.speechassist.aichat.bean.AIChatViewBean", loader);
        helper.getDeclaredMethod("b", String.class, String.class, Bundle.class, boolean.class);
        helper.getDeclaredMethod("d");
        helper.getDeclaredMethod("t", boolean.class, String.class, answer, Boolean.class, boolean.class, String.class);

        Class<?> room = XposedHelpers.findClass("com.heytap.speechassist.aichat.AIChatRoomIdManager", loader);
        room.getDeclaredField("INSTANCE");
        room.getDeclaredMethod("H");
        Class<?> provider = XposedHelpers.findClass("com.heytap.speechassist.aichat.AIChatViewBeanProvider", loader);
        provider.getDeclaredField("INSTANCE");
        provider.getDeclaredMethod("f", String.class, String.class, String.class);
        Class<?> dataCenter = XposedHelpers.findClass("com.heytap.speechassist.aichat.AIChatDataCenter", loader);
        dataCenter.getDeclaredField("INSTANCE");
        dataCenter.getDeclaredMethod("s", answer);

        Class<?> state = XposedHelpers.findClass("com.heytap.speechassist.aichat.state.AiChatState", loader);
        Class<?> subState = XposedHelpers.findClass("com.heytap.speechassist.aichat.state.AiChatSubState", loader);
        Class<?> stateManager = XposedHelpers.findClass(
                "com.heytap.speechassist.aichat.state.AiChatStateManager", loader);
        Class<?> stateManagerCompanion = stateManager.getDeclaredField(STATE_MANAGER_COMPANION_FIELD).getType();
        stateManagerCompanion.getDeclaredMethod("a");
        stateManager.getDeclaredMethod("h", state, boolean.class);
        stateManager.getDeclaredMethod("E", subState);

        Class<?> appContext = XposedHelpers.findClass("com.heytap.speechassist.utils.z2", loader);
        appContext.getDeclaredMethod("b");
        Class<?> preferences = XposedHelpers.findClass("com.heytap.speechassist.utils.z5", loader);
        preferences.getDeclaredMethod("q", Context.class);
        Class<?> common = XposedHelpers.findClass(
                "com.heytap.speechassist.aichat.floatwindow.utils.AiChatCommonHelperKt", loader);
        common.getDeclaredMethod("p", String.class, int.class, Object.class);
        Class<?> duplex = XposedHelpers.findClass("com.heytap.speechassist.utils.FullDuplexState", loader);
        duplex.getDeclaredField("INSTANCE");
        duplex.getDeclaredMethod("n");
        Class<?> modeManager = XposedHelpers.findClass("com.heytap.speechassist.core.w2", loader);
        Class<?> modeState = modeManager.getDeclaredMethod("a").getReturnType();
        modeState.getDeclaredMethod("S1");
        Class<?> tts = XposedHelpers.findClass(
                "com.heytap.speechassist.aichat.streamtts.AiChatTTSHighlightPlayManager", loader);
        tts.getDeclaredField("INSTANCE");
        tts.getDeclaredMethod("M");
    }

    private static void report(Context context, String text, boolean ready) {
        try {
            Bundle extras = new Bundle();
            extras.putInt("moduleVersion", ready ? BuildConfig.VERSION_CODE : 0);
            context.getContentResolver().call(ConfigProvider.URI, "status", text, extras);
        } catch (Throwable error) {
            XposedBridge.log("XiaoBuSwitch: status " + error);
        }
    }

    private static final class Runtime {
        private final Context context;
        private final ClassLoader loader;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newFixedThreadPool(2);
        private final AtomicLong generation = new AtomicLong();
        private final AtomicReference<ActiveRequest> active = new AtomicReference<>();
        private final Map<String, JSONArray> histories = new LinkedHashMap<>();
        private final Map<String, String> previousImages = new LinkedHashMap<>();
        private final BreenoImageBridge imageBridge;
        private Class<?> engineHelperClass;
        private long historyRevision = Long.MIN_VALUE;

        Runtime(Context context, ClassLoader loader) {
            this.context = context;
            this.loader = loader;
            this.imageBridge = new BreenoImageBridge(context, loader, this::currentRoom);
        }

        boolean install(Class<?> helper) {
            this.engineHelperClass = helper;
            Class<?> answerBean = XposedHelpers.findClass(
                    "com.heytap.speechassist.aichat.bean.AIChatViewBean", loader);
            XposedHelpers.findAndHookMethod(helper, "t", boolean.class, String.class, answerBean,
                    Boolean.class, boolean.class, String.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    cancelForNativeStop(param);
                }
            });
            XposedHelpers.findAndHookMethod(helper, "y", String.class, int.class, Bundle.class,
                    boolean.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            intercept(param);
                        }
                    });
            return imageBridge.install(this::interceptImageOnly);
        }

        private void intercept(XC_MethodHook.MethodHookParam param) {
            boolean intercepted = false;
            long requestId = -1L;
            String roomId = "";
            String recordId = "";
            ActiveRequest requestState = null;
            try {
                String text = (String) param.args[0];
                Bundle bundle = param.args[2] instanceof Bundle ? (Bundle) param.args[2] : null;
                Bundle bubbleBundle = bundle == null ? new Bundle() : new Bundle(bundle);
                boolean addQuery = Boolean.TRUE.equals(param.args[3]);
                String boxTool = bundle == null ? null : bundle.getString("extra_box_toolTag");
                boolean queryWithImage = bundle != null && (bundle.getBoolean("queryWithImage", false)
                        || "image".equals(bundle.getString("extra_doc_type")));
                ArrayList<String> docRecordIds = bundle == null ? null : bundle.getStringArrayList("extra_doc_recordid_list");
                XposedBridge.log("XiaoBuSwitch: y called len=" + (text == null ? -1 : text.length())
                        + " inputType=" + param.args[1] + " addQuery=" + param.args[3]
                        + " stateFlag=" + param.args[4] + " boxTool=" + boxTool
                        + " withImage=" + queryWithImage
                        + " docIds=" + (docRecordIds == null ? 0 : docRecordIds.size()));
                if (text == null || text.trim().isEmpty() || !addQuery) {
                    XposedBridge.log("XiaoBuSwitch: skip input flags");
                    return;
                }
                if (boxTool != null && !boxTool.isBlank()) {
                    XposedBridge.log("XiaoBuSwitch: skip boxTool=" + boxTool);
                    return;
                }

                JSONObject config = config();
                if (!config.optBoolean("enabled", false)) {
                    XposedBridge.log("XiaoBuSwitch: skip master disabled");
                    return;
                }
                QueryRouter.Selection selection = QueryRouter.select(text, "chat", false, queryWithImage);
                String route = selection.route();
                String queryText = selection.text();
                if (!route.equals("chat") && !route.equals("vision") && !route.equals("image")) {
                    XposedBridge.log("XiaoBuSwitch: skip unsupported route=" + route);
                    return;
                }
                Profile profile = ModeProfiles.resolve(config, route, false);
                if (!profile.enabled()) {
                    XposedBridge.log("XiaoBuSwitch: skip " + route + " disabled");
                    return;
                }
                if (!nativeGuardsAllow(param.thisObject)) return;

                roomId = currentRoom();
                if (roomId.isEmpty()) {
                    XposedBridge.log("XiaoBuSwitch: skip empty roomId");
                    return;
                }
                List<String> imageSources = queryWithImage
                        ? imageBridge.resolveSources(roomId, docRecordIds)
                        : Collections.emptyList();
                if (route.equals("vision") && imageSources.isEmpty()) {
                    XposedBridge.log("XiaoBuSwitch: skip vision without local image source");
                    return;
                }
                if (queryWithImage) {
                    imageBridge.clearRoomSnapshot(roomId);
                }
                XposedBridge.log("XiaoBuSwitch: intercepting route=" + route + " provider=" + profile.provider()
                        + " model=" + profile.model() + " images=" + imageSources.size());
                String suppliedRecordId = bubbleBundle.getString(RECORD_ID_KEY);
                recordId = suppliedRecordId == null || suppliedRecordId.isEmpty()
                        ? UUID.randomUUID().toString() : suppliedRecordId;
                if (suppliedRecordId == null || suppliedRecordId.isEmpty()) {
                    bubbleBundle.putString(RECORD_ID_KEY, recordId);
                }
                bubbleBundle.putBoolean("speech_in_aichat_room_flag", true);
                if (param.args[1] instanceof Integer) {
                    bubbleBundle.putInt("input_type", (Integer) param.args[1]);
                }

                long revision = config.optLong("revision", 0L);
                JSONArray history = history(roomId, revision);
                JSONObject historyRequest = ChatProtocol.request(profile, history,
                        !imageSources.isEmpty() ? "[附带图片] " + queryText : queryText);
                requestId = generation.incrementAndGet();
                ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
                requestState = new ActiveRequest(roomId, cancellation);
                ActiveRequest previous = active.getAndSet(requestState);
                if (previous != null) previous.cancellation.cancel();

                // Reuse Breeno's query bubble and generating state before suppressing its server request.
                XposedHelpers.callMethod(param.thisObject, "b", text, recordId, bubbleBundle,
                        Boolean.TRUE.equals(param.args[4]));
                param.setResult(null);
                intercepted = true;
                stopNativeTts();

                try {
                    final long currentRequestId = requestId;
                    final String currentRoomId = roomId;
                    final String currentRecordId = recordId;
                    final ActiveRequest currentRequest = requestState;
                    final List<String> currentImages = imageSources;
                    if (route.equals("image")) {
                        worker.execute(() -> askImage(currentRequestId, revision, currentRoomId, currentRecordId,
                                profile, queryText, currentImages, historyRequest, currentRequest));
                    } else {
                        worker.execute(() -> askDialogue(currentRequestId, revision, currentRoomId, currentRecordId,
                                config, route, profile, history, queryText, currentImages, historyRequest, currentRequest));
                    }
                } catch (RuntimeException rejected) {
                    active.compareAndSet(requestState, null);
                    showAnswer(requestId, roomId, recordId, "模型请求无法启动，请稍后重试。", false, null, null, Collections.emptyList());
                }
            } catch (Throwable error) {
                if (requestState != null) {
                    active.compareAndSet(requestState, null);
                    requestState.cancellation.cancel();
                }
                if (intercepted) {
                    showAnswer(requestId, roomId, recordId,
                            "模型请求失败：" + errorText(error), false, null, null, Collections.emptyList());
                }
                XposedBridge.log("XiaoBuSwitch: request " + error.getClass().getSimpleName());
            }
        }

        private void interceptImageOnly(XC_MethodHook.MethodHookParam param) {
            boolean intercepted = false;
            long requestId = -1L;
            String roomId = "";
            String recordId = "";
            ActiveRequest requestState = null;
            try {
                JSONObject config = config();
                if (!config.optBoolean("enabled", false)) return;
                Profile profile = ModeProfiles.resolve(config, "vision", false);
                if (!profile.enabled()) return;
                Object engine = engineHelperClass == null ? null
                        : XposedHelpers.getStaticObjectField(engineHelperClass, "INSTANCE");
                if (engine != null && !nativeGuardsAllow(engine)) return;

                roomId = currentRoom();
                if (roomId.isEmpty()) return;
                List<String> imageSources = imageBridge.captureHandleImages(param.thisObject, roomId);
                if (imageSources.isEmpty()) return;

                String prompt = imageSources.size() > 1 ? "请描述并分析这些图片。" : "请描述并分析这张图片。";
                recordId = UUID.randomUUID().toString();
                long revision = config.optLong("revision", 0L);
                JSONArray history = history(roomId, revision);
                JSONObject historyRequest = ChatProtocol.request(profile, history, "[附带图片] " + prompt);
                requestId = generation.incrementAndGet();
                ModelClient.Cancellation cancellation = new ModelClient.Cancellation();
                requestState = new ActiveRequest(roomId, cancellation);
                ActiveRequest previous = active.getAndSet(requestState);
                if (previous != null) previous.cancellation.cancel();

                XposedBridge.log("XiaoBuSwitch: intercepting image-only vision provider=" + profile.provider()
                        + " model=" + profile.model() + " images=" + imageSources.size());
                XposedHelpers.callMethod(param.thisObject, "F", roomId);
                imageBridge.clearRoomSnapshot(roomId);
                param.setResult(null);
                intercepted = true;
                setGenerating(roomId);
                stopNativeTts();

                final long currentRequestId = requestId;
                final String currentRoomId = roomId;
                final String currentRecordId = recordId;
                final ActiveRequest currentRequest = requestState;
                worker.execute(() -> askDialogue(currentRequestId, revision, currentRoomId, currentRecordId,
                        config, "vision", profile, history, prompt, imageSources, historyRequest, currentRequest));
            } catch (Throwable error) {
                if (requestState != null) {
                    active.compareAndSet(requestState, null);
                    requestState.cancellation.cancel();
                }
                if (intercepted) {
                    showAnswer(requestId, roomId, recordId,
                            "识图请求失败：" + errorText(error), false, null, null, Collections.emptyList());
                }
                XposedBridge.log("XiaoBuSwitch: image-only " + error.getClass().getSimpleName());
            }
        }

        private void cancelForNativeStop(XC_MethodHook.MethodHookParam param) {
            ActiveRequest running = active.get();
            if (running == null) return;
            try {
                Object bean = param.args[2];
                Object roomValue = bean == null ? null : XposedHelpers.callMethod(bean, "getRoomId");
                String room = roomValue instanceof String && !((String) roomValue).isEmpty()
                        ? (String) roomValue : currentRoom();
                // The stop path may not carry the request's customized ID, so scope it by active room.
                if (!running.roomId.equals(room)) return;
                if (active.compareAndSet(running, null)) {
                    generation.incrementAndGet();
                    running.cancellation.cancel();
                }
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: stop " + error.getClass().getSimpleName());
            }
        }

        private static String errorText(Throwable error) {
            String reason = error.getMessage();
            if (reason == null || reason.isBlank()) reason = "网络或模型服务异常";
            reason = reason.replace('\n', ' ').replace('\r', ' ');
            return reason.length() > 100 ? reason.substring(0, 100) : reason;
        }

        private void stopNativeTts() {
            try {
                Class<?> type = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.streamtts.AiChatTTSHighlightPlayManager", loader);
                Object manager = XposedHelpers.getStaticObjectField(type, "INSTANCE");
                XposedHelpers.callMethod(manager, "M");
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: stop TTS " + error.getClass().getSimpleName());
            }
        }

        private boolean nativeGuardsAllow(Object engine) {
            try {
                Class<?> appContext = XposedHelpers.findClass("com.heytap.speechassist.utils.z2", loader);
                Object ctx = XposedHelpers.callStaticMethod(appContext, "b");
                if (ctx == null) ctx = this.context;
                Class<?> preferences = XposedHelpers.findClass("com.heytap.speechassist.utils.z5", loader);
                if (!Boolean.TRUE.equals(XposedHelpers.callStaticMethod(preferences, "q", new Class<?>[]{Context.class}, ctx))) {
                    XposedBridge.log("XiaoBuSwitch: guard blocked by z5.q");
                    return false;
                }

                Class<?> common = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.floatwindow.utils.AiChatCommonHelperKt", loader);
                if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                        common, "p", new Class<?>[]{String.class, int.class, Object.class}, null, 1, null))) {
                    XposedBridge.log("XiaoBuSwitch: guard blocked by common.p");
                    return false;
                }

                Class<?> stateManagerClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.state.AiChatStateManager", loader);
                Object companion = XposedHelpers.getStaticObjectField(stateManagerClass, STATE_MANAGER_COMPANION_FIELD);
                Object stateManager = XposedHelpers.callMethod(companion, "a");
                Class<?> subStateClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.state.AiChatSubState", loader);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object dailyLimit = Enum.valueOf((Class<? extends Enum>) subStateClass.asSubclass(Enum.class), "DIALOG_LIMIT_TODAY");
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(stateManager, "E", dailyLimit))) {
                    XposedBridge.log("XiaoBuSwitch: guard blocked by DIALOG_LIMIT_TODAY");
                    return false;
                }

                Class<?> duplexClass = XposedHelpers.findClass("com.heytap.speechassist.utils.FullDuplexState", loader);
                Object duplex = XposedHelpers.getStaticObjectField(duplexClass, "INSTANCE");
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(duplex, "n"))) {
                    XposedBridge.log("XiaoBuSwitch: guard blocked by FullDuplexState.n");
                    return false;
                }

                Class<?> modeManagerClass = XposedHelpers.findClass("com.heytap.speechassist.core.w2", loader);
                Object modeManager = XposedHelpers.callStaticMethod(modeManagerClass, "a");
                int mode = ((Number) XposedHelpers.callMethod(modeManager, "S1")).intValue();
                if (mode == 15 || mode == 16 || mode == 21 || mode == 22) {
                    XposedBridge.log("XiaoBuSwitch: guard blocked by mode=" + mode);
                    return false;
                }
                boolean engineD = Boolean.TRUE.equals(XposedHelpers.callMethod(engine, "d"));
                if (engineD) XposedBridge.log("XiaoBuSwitch: guard blocked by engine.d");
                return !engineD;
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: native guard " + error.getClass().getSimpleName());
                XposedBridge.log(error);
                return false;
            }
        }

        private void askImage(long requestId, long revision, String roomId, String recordId, Profile profile,
                              String prompt, List<String> imageSources, JSONObject historyRequest,
                              ActiveRequest requestState) {
            try {
                List<ModelClient.Image> originals = imageSources.isEmpty()
                        ? Collections.emptyList()
                        : imageBridge.loadImages(imageSources, requestState.cancellation);
                List<BreenoImageBridge.RenderedImage> rendered = generateImages(roomId, profile, prompt, originals,
                        requestState.cancellation);
                if (rendered.isEmpty()) {
                    throw new IllegalStateException("绘图服务未返回有效图片");
                }
                if (!isCurrent(requestId, revision)) {
                    resetIfLatest(requestId, roomId);
                    return;
                }
                showAnswer(requestId, roomId, recordId, "已为你生成图片：", true, historyRequest, profile, rendered);
            } catch (java.util.concurrent.CancellationException ignored) {
                // Superseded by a newer request or settings change.
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: image failed " + error.getClass().getSimpleName() + ": " + errorText(error));
                if (!isCurrent(requestId, revision)) {
                    resetIfLatest(requestId, roomId);
                    return;
                }
                showAnswer(requestId, roomId, recordId, "绘图失败：" + errorText(error), false, null, null, Collections.emptyList());
            } finally {
                active.compareAndSet(requestState, null);
            }
        }

        private void askDialogue(long requestId, long revision, String roomId, String recordId, JSONObject config,
                                 String route, Profile profile, JSONArray history, String text,
                                 List<String> imageSources, JSONObject historyRequest, ActiveRequest requestState) {
            try {
                List<ModelClient.Image> attached = imageSources.isEmpty()
                        ? Collections.emptyList()
                        : imageBridge.loadImages(imageSources, requestState.cancellation);
                if (route.equals("vision") && attached.isEmpty()) {
                    throw new IllegalStateException("未读取到有效图片附件");
                }
                List<String> attachments = new ArrayList<>(attached.size());
                for (ModelClient.Image image : attached) {
                    attachments.add(BreenoImageBridge.dataUrl(image));
                }
                Profile imageProfile = ModeProfiles.resolve(config, "image", false);
                boolean imageTool = config.optBoolean("autoRoute", true) && imageProfile.enabled();
                DialogueProtocol.Tools tools = new DialogueProtocol.Tools(imageTool, false, false);
                JSONObject request = DialogueProtocol.request(profile, history, text, attachments, tools);
                List<BreenoImageBridge.RenderedImage> renderedImages = new ArrayList<>();
                String answer;
                if (imageTool) {
                    DialogueLoop.Result result = DialogueLoop.run(profile, request, tools, task -> {
                        if (!isCurrent(requestId, revision)) throw new java.util.concurrent.CancellationException();
                        List<ModelClient.Image> originals = new ArrayList<>();
                        if ("input".equals(task.image())) {
                            if (attached.isEmpty()) throw new IllegalArgumentException("请先附加原图");
                            originals.addAll(attached);
                        } else if ("previous".equals(task.image())) {
                            String prev = previousImage(roomId);
                            if (prev.isEmpty()) throw new IllegalArgumentException("当前会话没有上次生成的图片");
                            originals.add(imageBridge.loadSource(prev, requestState.cancellation));
                        }
                        List<BreenoImageBridge.RenderedImage> generated = generateImages(roomId, imageProfile,
                                task.text(), originals, requestState.cancellation);
                        renderedImages.addAll(generated);
                        List<String> uris = new ArrayList<>(generated.size());
                        for (BreenoImageBridge.RenderedImage item : generated) uris.add(item.fileUri());
                        return new DialogueLoop.Result("已生成图片", uris);
                    }, () -> isCurrent(requestId, revision), requestState.cancellation,
                            (selected, bodyPart, cancel) -> chat(selected, bodyPart, cancel));
                    answer = result.text();
                } else {
                    JSONObject response = chat(profile, request, requestState.cancellation);
                    answer = ChatProtocol.answer(response);
                }
                XposedBridge.log("XiaoBuSwitch: " + route + " answer received len=" + (answer == null ? -1 : answer.length())
                        + " generatedImages=" + renderedImages.size());
                if (!isCurrent(requestId, revision)) {
                    resetIfLatest(requestId, roomId);
                    return;
                }
                showAnswer(requestId, roomId, recordId, answer, true, historyRequest, profile, renderedImages);
            } catch (java.util.concurrent.CancellationException ignored) {
                // Superseded by a newer request or settings change.
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: " + route + " failed " + error.getClass().getSimpleName() + ": " + errorText(error));
                if (!isCurrent(requestId, revision)) {
                    resetIfLatest(requestId, roomId);
                    return;
                }
                String prefix = route.equals("vision") ? "识图请求失败：" : "模型请求失败：";
                String extra = route.equals("vision") && error instanceof ModelClient.HttpError ? "（请确认所选模型支持图片输入）" : "";
                showAnswer(requestId, roomId, recordId, prefix + errorText(error) + extra, false, null, null, Collections.emptyList());
            } finally {
                active.compareAndSet(requestState, null);
            }
        }

        private List<BreenoImageBridge.RenderedImage> generateImages(String roomId, Profile profile, String prompt,
                                                                   List<ModelClient.Image> originals,
                                                                   ModelClient.Cancellation cancellation) throws Exception {
            if (!profile.enabled()) throw new IllegalArgumentException("绘图通道未启用");
            JSONObject response;
            if ("codex_subscription".equals(profile.provider())) {
                response = subscriptionImage(profile, prompt, originals, cancellation);
            } else if (!originals.isEmpty()) {
                response = ModelClient.edit(profile, prompt, originals, cancellation);
            } else {
                response = ModelClient.post(profile, profile.imageUrl(), ChatProtocol.imageRequest(profile, prompt), cancellation);
            }
            List<String> sources = ImageProtocol.sources(response);
            List<BreenoImageBridge.RenderedImage> rendered = imageBridge.materializeGenerated(sources, cancellation);
            if (!rendered.isEmpty() && roomId != null && !roomId.isEmpty()) {
                synchronized (previousImages) {
                    previousImages.put(roomId, rendered.get(rendered.size() - 1).fileUri());
                    while (previousImages.size() > 8) {
                        previousImages.remove(previousImages.keySet().iterator().next());
                    }
                }
            }
            return rendered;
        }

        private JSONObject subscriptionImage(Profile profile, String prompt, List<ModelClient.Image> originals,
                                             ModelClient.Cancellation cancellation) throws Exception {
            CodexOAuth.Tokens tokens = credentials(false, null);
            try {
                return CodexClient.images(profile, prompt, originals, tokens, cancellation);
            } catch (ModelClient.HttpError error) {
                if (error.status() != 401) throw error;
                return CodexClient.images(profile, prompt, originals, credentials(true, tokens.accessToken()), cancellation);
            }
        }

        private String previousImage(String roomId) {
            synchronized (previousImages) {
                String value = previousImages.get(roomId);
                return value == null ? "" : value;
            }
        }

        private JSONObject chat(Profile profile, JSONObject request, ModelClient.Cancellation cancellation) throws Exception {
            if (!"codex_subscription".equals(profile.provider())) {
                return ModelClient.chat(profile, request, cancellation);
            }
            CodexOAuth.Tokens tokens = credentials(false, null);
            try {
                return CodexClient.chat(profile, request, tokens, cancellation);
            } catch (ModelClient.HttpError error) {
                if (error.status() != 401) throw error;
                return CodexClient.chat(profile, request, credentials(true, tokens.accessToken()), cancellation);
            }
        }

        private CodexOAuth.Tokens credentials(boolean force, String previousAccess) throws Exception {
            Bundle extras = new Bundle();
            extras.putBoolean("force", force);
            if (previousAccess != null) extras.putString("previousAccess", previousAccess);
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "codexCredentials", null, extras);
            if (result == null || result.containsKey("error")) throw new CodexClient.LoginRequired();
            return new CodexOAuth.Tokens(result.getString("accessToken", ""), "", "",
                    result.getString("accountId", ""), "", "", result.getLong("expiresAt"));
        }

        private JSONObject config() throws Exception {
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "config", null, null);
            return new JSONObject(result == null ? "{}" : result.getString("json", "{}"));
        }

        private JSONArray history(String roomId, long revision) {
            synchronized (histories) {
                if (historyRevision != revision) {
                    histories.clear();
                    synchronized (previousImages) {
                        previousImages.clear();
                    }
                    historyRevision = revision;
                }
                JSONArray value = histories.get(roomId);
                if (value == null) return new JSONArray();
                try {
                    return new JSONArray(value.toString());
                } catch (org.json.JSONException error) {
                    XposedBridge.log("XiaoBuSwitch: history copy " + error.getClass().getSimpleName());
                    histories.remove(roomId);
                    return new JSONArray();
                }
            }
        }

        private boolean isCurrent(long requestId, long revision) {
            if (generation.get() != requestId) return false;
            try {
                JSONObject latest = config();
                return latest.optBoolean("enabled", false) && latest.optLong("revision", 0L) == revision;
            } catch (Exception error) {
                return false;
            }
        }

        private void resetIfLatest(long requestId, String roomId) {
            main.post(() -> {
                if (generation.get() == requestId) setIdle(roomId);
            });
        }

        private String currentRoom() {
            Class<?> type = XposedHelpers.findClassIfExists(
                    "com.heytap.speechassist.aichat.AIChatRoomIdManager", loader);
            if (type == null) return "";
            Object manager = XposedHelpers.getStaticObjectField(type, "INSTANCE");
            Object value = XposedHelpers.callMethod(manager, "H");
            return value instanceof String ? (String) value : "";
        }

        private void showAnswer(long requestId, String roomId, String recordId, String answer,
                                boolean addHistory, JSONObject request, Profile profile,
                                List<BreenoImageBridge.RenderedImage> renderedImages) {
            main.post(() -> {
                if (generation.get() != requestId) return;
                try {
                    if (!roomId.equals(currentRoom())) return;
                    if (addHistory && request != null && profile != null) {
                        synchronized (histories) {
                            histories.put(roomId, ChatProtocol.history(request, answer));
                            while (histories.size() > 8) histories.remove(histories.keySet().iterator().next());
                        }
                    }
                    boolean hasImages = renderedImages != null && !renderedImages.isEmpty();
                    String displayContent = hasImages ? BreenoImageBridge.appendImagePlaceholder(answer) : answer;
                    Class<?> providerClass = XposedHelpers.findClass(
                            "com.heytap.speechassist.aichat.AIChatViewBeanProvider", loader);
                    Object provider = XposedHelpers.getStaticObjectField(providerClass, "INSTANCE");
                    Object bean = XposedHelpers.callMethod(provider, "f", recordId, roomId, displayContent);
                    XposedHelpers.callMethod(bean, "setSkillType", "MyAI.StreamTextCard");
                    XposedHelpers.callMethod(bean, "setFinal", Boolean.TRUE);
                    XposedHelpers.callMethod(bean, "setFirstSlice", true);
                    XposedHelpers.callMethod(bean, "setHasTextPrintAnimPlayed", true);
                    if (hasImages) {
                        imageBridge.attachMarkdownImages(bean, renderedImages);
                    }
                    Class<?> centerClass = XposedHelpers.findClass(
                            "com.heytap.speechassist.aichat.AIChatDataCenter", loader);
                    Object center = XposedHelpers.getStaticObjectField(centerClass, "INSTANCE");
                    XposedHelpers.callMethod(center, "s", bean);
                } catch (Throwable error) {
                    XposedBridge.log("XiaoBuSwitch: render " + error.getClass().getSimpleName());
                } finally {
                    setIdle(roomId);
                }
            });
        }

        private void setGenerating(String roomId) {
            try {
                if (!roomId.equals(currentRoom())) return;
                Class<?> stateManagerClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.state.AiChatStateManager", loader);
                Object companion = XposedHelpers.getStaticObjectField(stateManagerClass, STATE_MANAGER_COMPANION_FIELD);
                Object manager = XposedHelpers.callMethod(companion, "a");
                Class<?> stateClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.state.AiChatState", loader);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object generating = Enum.valueOf((Class<? extends Enum>) stateClass.asSubclass(Enum.class), "GENERATING");
                XposedHelpers.callMethod(manager, "h", generating, true);
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: generating state " + error.getClass().getSimpleName());
            }
        }

        private void setIdle(String roomId) {
            try {
                if (!roomId.equals(currentRoom())) return;
                Class<?> stateManagerClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.state.AiChatStateManager", loader);
                Object companion = XposedHelpers.getStaticObjectField(stateManagerClass, STATE_MANAGER_COMPANION_FIELD);
                Object manager = XposedHelpers.callMethod(companion, "a");
                Class<?> stateClass = XposedHelpers.findClass(
                        "com.heytap.speechassist.aichat.state.AiChatState", loader);
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object idle = Enum.valueOf((Class<? extends Enum>) stateClass.asSubclass(Enum.class), "IDLE");
                XposedHelpers.callMethod(manager, "h", idle, false);
            } catch (Throwable error) {
                XposedBridge.log("XiaoBuSwitch: state " + error.getClass().getSimpleName());
            }
        }

        private static final class ActiveRequest {
            final String roomId;
            final ModelClient.Cancellation cancellation;

            ActiveRequest(String roomId, ModelClient.Cancellation cancellation) {
                this.roomId = roomId;
                this.cancellation = cancellation;
            }
        }
    }
}
