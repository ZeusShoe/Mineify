package com.mineify.client;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.List;

public final class VoiceDuckingDetector {
    private static final List<String> SIMPLE_VOICECHAT_MOD_IDS = List.of("voicechat", "simplevoicechat", "simple-voice-chat");
    private static final List<String> PLASMO_VOICE_MOD_IDS = List.of("plasmovoice", "plasmovoice-client", "pv");

    private static boolean simpleResolved = false;
    private static Object simpleTarget = null;
    private static Method simpleMethod = null;

    private static boolean plasmoResolved = false;
    private static Object plasmoTarget = null;
    private static Method plasmoMethod = null;

    private VoiceDuckingDetector() {}

    public static boolean isVoiceActive() {
        boolean active = false;
        if (isAnyModLoaded(SIMPLE_VOICECHAT_MOD_IDS)) {
            active |= isSimpleVoiceChatActive();
        }
        if (isAnyModLoaded(PLASMO_VOICE_MOD_IDS)) {
            active |= isPlasmoVoiceActive();
        }
        return active;
    }

    private static boolean isAnyModLoaded(List<String> modIds) {
        FabricLoader loader = FabricLoader.getInstance();
        for (String id : modIds) {
            if (loader.isModLoaded(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSimpleVoiceChatActive() {
        if (!simpleResolved) {
            simpleResolved = true;
            resolveSimpleVoiceChat();
        }
        return invokeBoolean(simpleTarget, simpleMethod);
    }

    private static boolean isPlasmoVoiceActive() {
        if (!plasmoResolved) {
            plasmoResolved = true;
            resolvePlasmoVoice();
        }
        return invokeBoolean(plasmoTarget, plasmoMethod);
    }

    private static void resolveSimpleVoiceChat() {
        String[] classNames = {
                "de.maxhenkel.voicechat.api.VoicechatClientApi",
                "de.maxhenkel.voicechat.api.client.VoicechatClientApi",
                "de.maxhenkel.voicechat.VoicechatClient",
                "de.maxhenkel.voicechat.voice.client.VoicechatClient"
        };
        String[] methodNames = {
                "isSpeaking",
                "isTalking",
                "isRecording",
                "isMicrophoneActive",
                "isVoiceActive"
        };
        resolveApi(classNames, methodNames, true);
    }

    private static void resolvePlasmoVoice() {
        String[] classNames = {
                "su.plo.voice.api.client.PlasmoVoiceClientApi",
                "su.plo.voice.api.client.PlasmoVoiceClient",
                "su.plo.voice.client.PlasmoVoiceClient",
                "su.plo.voice.api.PlasmoVoice"
        };
        String[] methodNames = {
                "isSpeaking",
                "isTalking",
                "isRecording",
                "isMicrophoneActive",
                "isVoiceActive"
        };
        resolveApi(classNames, methodNames, false);
    }

    private static void resolveApi(String[] classNames, String[] methodNames, boolean simple) {
        for (String className : classNames) {
            try {
                Class<?> clazz = Class.forName(className);
                Object target = resolveTarget(clazz);
                Method method = findBooleanMethod(clazz, methodNames);
                if (method != null) {
                    if (simple) {
                        simpleTarget = target;
                        simpleMethod = method;
                    } else {
                        plasmoTarget = target;
                        plasmoMethod = method;
                    }
                    return;
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Exception ignored) {
            }
        }
    }

    private static Object resolveTarget(Class<?> clazz) throws Exception {
        try {
            Method m = clazz.getMethod("getInstance");
            return m.invoke(null);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method m = clazz.getMethod("instance");
            return m.invoke(null);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Method m = clazz.getMethod("getApi");
            return m.invoke(null);
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private static Method findBooleanMethod(Class<?> clazz, String[] names) {
        for (String name : names) {
            try {
                Method m = clazz.getMethod(name);
                if (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static boolean invokeBoolean(Object target, Method method) {
        if (method == null) {
            return false;
        }
        try {
            Object result = method.invoke(target);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
