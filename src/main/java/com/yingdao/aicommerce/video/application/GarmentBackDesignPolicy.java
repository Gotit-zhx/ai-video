package com.yingdao.aicommerce.video.application;

/** Shared prompt policy; it does not itself inspect pixels or claim to enforce image contents. */
final class GarmentBackDesignPolicy {
    private GarmentBackDesignPolicy() {}

    static final String PROMPT = "【背面图案来源规则】先核对用户上传的当前商品参考：背面证据可以是独立背面图，"
            + "也可以是同一张正背多视角合成图中可明确定位的背面区域；不得根据图片数量猜测是否提供了背面。"
            + "只有用户明确提供、且能确认属于当前商品背面的花纹、印花、Logo或文字，才按该背面证据保留对应内容、位置与比例。"
            + "没有明确的背面图案参考或无法确认时，生成的后背不添加图案、印花、Logo或文字；"
            + "禁止把正面内容复制、镜像、环绕延续到后背，也禁止依据品类、想象或脚本旧商品补画背面图案。"
            + "后背无图案不等于无结构：保留商品面料、底色、真实织物肌理、缝线、剪裁、开口、肩带和其他有证据的背部结构；"
            + "不得抹掉用户已明确提供的真实背面花纹，也不得改动正面已经确认的图案。"
            + "已生成主图、九宫格、脚本视频和身份锁定板不是用户新增的背面图案授权，不能把其中幻觉当成新证据。"
            + "‘每格图案一致、Logo不得丢失、细节不得删除’只要求保留对应可见面的真实内容，不要求正面印花出现在背面。"
            + "本规则适用于主图、九宫格每一格及视频转身后的每一帧；后背朝向镜头时不得为展示正面印花而扭曲衣服或把人转回正面。";
}
