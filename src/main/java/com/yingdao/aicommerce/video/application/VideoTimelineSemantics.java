package com.yingdao.aicommerce.video.application;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VideoTimelineSemantics {

    static final String FIRST_SEGMENT = "无";
    static final String CONTINUOUS_SEGMENT = "同镜头连续，无转场";
    static final String TRANSITION_POLICY = "转场只服从每段的“进入转场”：首段无转场；未填写、标为无或同镜头连续时，延续同一真实镜头。"
            + "只有明确写出的真实切点或转场才允许切换，场景、构图或手势差异本身不能新增切镜。"
            + "连续段保持同一场景与手臂身份；若参考格的背景不兼容，保留当前场景，不强行混合背景。"
            + "真实切点两侧不做人体或手势变形插值；不得用残影、复制肢体、多手或同时显示两格代替转场。";

    private static final String NEGATION = "(?:禁止|避免|不要|不得|不可|不能|无需|不需要|勿|不|\\b(?:no|not|never|without|avoid)\\b)";
    private static final String NEGATION_MODIFIERS = "(?:\\s|再|继续|使用|采用|执行|进行|出现|任何|镜头|机位|缓慢|逐渐|拍摄|展示|显示|拍到|呈现|改成|变成|额外|新增|\\b(?:a|any|the|use|show|include|slowly|camera)\\b)*";
    private static final String FRAMING_LIST_ITEM = "(?:(?:完整)?全身|大全景|远景|全景)(?:镜头|构图|画面|景别|入镜|出镜)?"
            + "|(?:后退)?拉远(?:镜头)?|后退|退后|远离|(?:双脚|脚部|鞋子)(?:入镜|可见|特写)?"
            + "|(?:wide|long)\\s+(?:shot|view)|full[- ]body(?:\\s+(?:shot|view|framing))?"
            + "|zoom(?:s|ing)?\\s*out|dolly\\s*out|pull(?:s|ing)?\\s*back|truck\\s*out";
    private static final Pattern NEGATED_PREFIX = Pattern.compile("(?i)" + NEGATION + NEGATION_MODIFIERS + "$");
    // Only propagate through a list of known framing constraints, never through a sentence or an action.
    private static final Pattern NEGATED_LIST_PREFIX = Pattern.compile("(?i)" + NEGATION + NEGATION_MODIFIERS
            + "(?:(?:" + FRAMING_LIST_ITEM + ")\\s*(?:[、，,]|和|或|以及|\\band\\b|\\bor\\b)"
            + NEGATION_MODIFIERS + ")+$");
    private static final Pattern EXPLICIT_CONTINUITY = Pattern.compile(
            "(?i)(?:^|[，,。;；])\\s*(?:同(?:一)?镜头连续|无转场)(?:$|[，,。;；])"
                    + "|(?:^|[，,。;；])\\s*(?:same[- ]shot|no transition|continuous shot)(?:$|[，,。;；])");

    private VideoTimelineSemantics() {
    }

    static String enteringTransition(int sequence, String transitionDescription) {
        if (sequence <= 1) return FIRST_SEGMENT;
        if (transitionDescription == null || transitionDescription.isBlank()
                || transitionDescription.trim().matches("(?i)无[。.]?|无转场[。.]?|none|no transition")
                || EXPLICIT_CONTINUITY.matcher(transitionDescription).find()) {
            return CONTINUOUS_SEGMENT;
        }
        return transitionDescription.trim();
    }

    static String normalizeCameraMovement(String generationMode, String movement) {
        if (movement == null || movement.isBlank()) return "";
        // In this typed field, retreat is a camera instruction, not a person's valid step.
        return rewrite(normalizeCloseFraming(generationMode, movement),
                "(?:后退|退后|远离)(?:\\d+(?:\\.\\d+)?米)?", "保持固定机位");
    }

    /** Rewrites affirmative framing conflicts only; leaves garment details and valid actions intact. */
    static String normalizeCloseFraming(String generationMode, String description) {
        if (description == null || description.isBlank()) return "";
        boolean productOnly = "PRODUCT_ONLY".equals(generationMode);
        String framing = productOnly ? "商品近景" : "人物中近景";
        String result = rewrite(description.trim(),
                "(?:镜头|机位|摄影机|相机)(?:缓慢|逐渐|轻微|继续)?(?:向后|往后)?(?:后退|退后|拉远|远离)(?:\\d+(?:\\.\\d+)?米)?"
                        + "|\\bcamera\\s+(?:slowly\\s+)?(?:moves?\\s+back(?:wards)?|retreats?)\\b",
                "镜头保持固定机位");
        result = rewrite(result,
                "(?:缓慢|逐渐|轻微)?(?:后退拉远|拉远(?:镜头)?|拉开(?:镜头)?距离)"
                        + "|\\b(?:zoom(?:s|ing)?\\s*out|dolly\\s*out|pull(?:s|ing)?\\s*back(?!\\s+(?:(?:the|a)\\s+)?(?:sleeves?|fabric|garment|collar|hem)\\b)|truck\\s*out)\\b",
                "保持固定机位");
        result = rewrite(result, "(?:切到|切至|拉到|拉至)(?:远景|全景|完整全身|全身)", "保持" + framing);
        result = rewrite(result,
                "大全景|远景|全景|(?:完整)?全身(?=镜头|构图|画面|景别|出镜|入镜|展示|站立|站姿|[\\s，,。.;；]|$)"
                        + "|\\b(?:wide|long)\\s+(?:shot|view)\\b|\\bfull[- ]body(?:\\s+(?:shot|view|framing))?\\b(?!\\s+(?:print|pattern|fabric))",
                framing);
        if (!productOnly) {
            result = rewrite(result,
                    "(?:展示|露出|拍摄|拍到|特写|对准)(?:双脚|脚部|鞋子|鞋面|鞋底)"
                            + "|(?:双脚|脚部|鞋子)(?:清晰)?(?:入镜|可见|特写)",
                    "保持膝盖以上构图，膝盖以下留在画外");
        }
        return result;
    }

    private static String rewrite(String text, String expression, String replacement) {
        Matcher matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(text);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            String prefix = text.substring(0, matcher.start());
            boolean negated = NEGATED_PREFIX.matcher(prefix).find()
                    || NEGATED_LIST_PREFIX.matcher(prefix).find();
            // A distant background is compatible with a close subject; don't flatten its depth.
            boolean background = prefix.matches("(?is).*(?:背景|窗外|背景中的|背景为|background\\s+)$");
            boolean garmentPull = matcher.group().contains("拉远")
                    && prefix.matches("(?s).*(?:衣摆|袖子|衣领|布料|袖口)(?:轻轻|轻微|缓慢)?$");
            matcher.appendReplacement(rewritten,
                    Matcher.quoteReplacement(negated || background || garmentPull ? matcher.group() : replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
}
