package com.yingdao.aicommerce.video.application;

import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Fits retained scenes into the existing nine-cell reference renderer without restoring deleted content. */
final class StoryboardShotPlan {
    private StoryboardShotPlan() {}
    static String forGeneration(String json, ObjectMapper mapper) {
        try {
            Map<String,Object> root = new LinkedHashMap<>(mapper.readValue(json, Map.class));
            List<Map<String,Object>> shots = new ArrayList<>();
            for (Object value : (List<?>)root.get("shots")) shots.add(new LinkedHashMap<>((Map<String,Object>)value));
            if (shots.size() == 9) return json;
            if (shots.isEmpty() || shots.size() > 9) throw new IllegalArgumentException("脚本需要 1 至 9 个分镜");
            root.put("retainedSceneCount", shots.size());
            for (Map<String,Object> shot : shots) shot.putIfAbsent("sourceSequence",shot.get("sequence"));
            while (shots.size() < 9) {
                int longest = 0;
                for (int i=1;i<shots.size();i++) if (duration(shots.get(i)).compareTo(duration(shots.get(longest)))>0) longest=i;
                Map<String,Object> first=shots.get(longest), next=new LinkedHashMap<>(first);
                BigDecimal start=number(first.get("startSecond")), end=number(first.get("endSecond"));
                BigDecimal middle=start.add(end).divide(new BigDecimal("2"),6,RoundingMode.HALF_UP);
                first.put("endSecond",middle);first.put("durationSeconds",middle.subtract(start));
                next.put("startSecond",middle);next.put("durationSeconds",end.subtract(middle));
                next.put("narration","");next.put("transitionDescription","连续镜头，承接同一动作，不重新开始动作");
                shots.add(longest+1,next);
            }
            for(int i=0;i<shots.size();i++) shots.get(i).put("sequence",i+1);
            root.put("shots",shots);
            return mapper.writeValueAsString(root);
        } catch (Exception error) { throw new IllegalArgumentException("无法编排保留分镜的生成时间线",error); }
    }
    private static BigDecimal duration(Map<String,Object> shot) {return number(shot.get("endSecond")).subtract(number(shot.get("startSecond")));}
    private static BigDecimal number(Object value) {return new BigDecimal(String.valueOf(value));}
}
