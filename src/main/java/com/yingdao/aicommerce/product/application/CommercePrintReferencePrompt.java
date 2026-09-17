package com.yingdao.aicommerce.product.application;

/** One physical product specification, with camera directions derived from a shared frame. */
final class CommercePrintReferencePrompt {
    static final String VERSION = "garment-four-view-v3";
    private CommercePrintReferencePrompt() {}

    static String build(boolean alreadyPlain) {
        String cleanup = alreadyPlain
                ? "The uploaded product is already plain. Preserve its existing plain fabric colors and structural color panels; do not add any artwork or logos."
                : "Remove the product's decorative prints, logos, graphic embroidery and decorative lettering, restoring the neighboring plain base fabric. Remove overlaid photographic watermarks. Preserve structural seams, ribbing, buttons, binding, trims and genuinely separate fabric color panels; these are not printed artwork.";
        return """
                Create exactly ONE square 2-by-2 reference sheet showing the SAME physical product in four views.
                The attached photograph is the sole product identity reference. First establish ONE coherent physical construction using the evidence rules below, then freeze that construction and pose before rendering all four views. Rotate only the camera around it. Do not independently redesign each panel.
                Preserve the original product category, cut, structural length-to-width proportions, fabric colors and material, neckline, shoulder heights, sleeve style and length, cuffs, seams, verified layered hems, exposed straps and asymmetric construction details supported by clear evidence. Distinguish the garment's sewn construction from temporary folds and styling in the photograph: relax the photographed pose as specified below without redesigning the garment. Do not turn an asymmetric, off-shoulder, cap-sleeve or dolman garment into a generic round-neck T-shirt. Do not turn an arbitrary product into clothing.
                """ + cleanup + """

                STRUCTURE EVIDENCE: Before rendering, internally distinguish permanent garment construction from temporary folding, then settle on ONE consistent construction for the four views. A rolled or lifted edge, exposed reverse side of the same fabric, overlapping hem folds, an angled crease or a cast shadow does NOT prove a second fabric layer, diagonal cut, wrap panel or permanent asymmetric hem. Preserve permanent overlaps and layers only when clearly visible seams, distinct fabric pieces or actual structural connections support them. A clearly evidenced separate inner layer, including one with a different fabric color, must remain; the reverse side revealed by a temporary fold is not such evidence.
                For an ordinary T-shirt, retain its evidenced width, loose fit, dropped shoulders and sleeve proportions, while using a straight body and one continuous single-layer level hem after releasing temporary folds. Do not turn a normal straight T-shirt into an asymmetric design to imitate its photographed folds. Depart from that ordinary construction only for clear evidence of an intentionally different cut. When a hem is partly hidden or ambiguous, use the simplest continuous relaxed single-layer hem consistent with the visible garment construction: do not invent a diagonal cut, second layer, crossed front panels, false side slit or detached flap. Keep clearly evidenced genuine asymmetric, off-shoulder and layered garments as their own construction; this rule does not replace them with a generic T-shirt.

                CLOTHING POSE: choose ONE naturally relaxed, gently opened ghost-mannequin pose, supported by an invisible body, and keep that exact pose in all four views. The chest and back have plausible internal space and softly hanging fabric; sleeves retain separate open cavities. Keep the original loose fit, dropped shoulders, broad sleeves and intentional drape when present. Do not stretch the cut, flatten it into a paper sheet, collapse the chest, inflate it like a balloon or make it rigid. Do not show a person, skin, body parts, mannequin, hanger or support. For other products retain the original natural three-dimensional form.
                Release temporary tucks, rolled-up edges, twists, knots and styled folds in the source photo. Let the hem hang freely under gravity, with its actual edge and length readable: no newly folded-over, tucked-in, knotted or bunched-up hem. Preserve intentionally sewn asymmetric hems, permanent overlaps, pleats and layered construction only when supported by the structure evidence above; do not straighten an evidenced designed asymmetry into a different cut.
                Let sleeves rest slightly outward and downward from the torso so their outline and underarm transition are distinguishable in front and back views where the actual construction permits. Preserve dropped-shoulder and wide-sleeve proportions; do not lift the sleeves into a rigid T-pose, narrow them or invent gaps in a joined dolman construction. Keep natural occlusion in exact side views. Show the collar rim, sleeve cuffs and actual opening depth clearly wherever visible; do not seal existing openings or invent extra holes.
                Retain small natural gravity folds and fabric texture. Avoid deep styling folds or large overlapping bunches that hide the chest, sleeve roots, side seams or hem. This is a relaxed structural reference, not a fashion pose; do not remove structural pleats or genuine layered details to make the fabric artificially smooth.
                Keep all asymmetric features on their original wearer-relative side in every panel; do not force the garment to be symmetric. Make only one conservative hypothesis for unseen construction and reuse it consistently; do not claim inferred unseen details are measured.

                Fixed layout, equal square cells, no gutter, dividing line, captions, direction letters, logos or text:
                TOP LEFT = FRONT; TOP RIGHT = BACK; BOTTOM LEFT = wearer's LEFT side; BOTTOM RIGHT = wearer's RIGHT side.
                Shared world frame: +Z is the garment front/chest; +X is the wearer's LEFT; +Y is up.
                FRONT camera is on +Z, so the wearer's RIGHT appears on the image LEFT.
                BACK camera is on -Z, so the wearer's RIGHT appears on the image RIGHT.
                LEFT camera is on +X: the chest/front points toward the image LEFT.
                RIGHT camera is on -X: the chest/front points toward the image RIGHT.
                Use strict orthographic front, back and exact 90-degree side views, not three-quarter views or perspective. Do not mirror a front image to invent a back or side image.
                The product has the same physical height, pixel scale, vertical alignment and unchanged pose in all four cells. Side-view depth is not front-view width. Keep the entire item including straps and hem inside each cell, with at least 8% clear margin; no clipping or overlap across cells.
                Use a clean uniform pure white background, consistent soft neutral studio illumination, visible fabric structure and readable open neck/sleeve cavities. Background white does not mean recoloring the garment white. Output only this one four-view sheet.
                """;
    }
}
