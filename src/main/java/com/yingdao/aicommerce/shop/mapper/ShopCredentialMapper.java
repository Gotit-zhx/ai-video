package com.yingdao.aicommerce.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yingdao.aicommerce.shop.domain.ShopCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ShopCredentialMapper extends BaseMapper<ShopCredential> {
    @Select("""
            <script>
            SELECT shop_id, organization_id, token_group_id, access_token_ciphertext,
                   refresh_token_ciphertext, access_token_expires_at,
                   refresh_token_expires_at, created_at, updated_at, version
              FROM shop_credentials
             WHERE organization_id = #{organizationId}
               AND shop_id IN
               <foreach collection="shopIds" item="shopId" open="(" separator="," close=")">
                   #{shopId}
               </foreach>
            </script>
            """)
    List<ShopCredential> selectForShops(
            @Param("organizationId") String organizationId,
            @Param("shopIds") List<String> shopIds);

    @Select("""
            SELECT grouped.shop_id, grouped.organization_id, grouped.token_group_id,
                   grouped.access_token_ciphertext, grouped.refresh_token_ciphertext,
                   grouped.access_token_expires_at, grouped.refresh_token_expires_at,
                   grouped.created_at, grouped.updated_at, grouped.version
              FROM shop_credentials grouped
              JOIN shop_credentials target
                ON target.shop_id = #{shopId}
               AND target.organization_id = #{organizationId}
               AND grouped.organization_id = target.organization_id
               AND grouped.token_group_id = target.token_group_id
             ORDER BY grouped.shop_id
             FOR UPDATE
            """)
    List<ShopCredential> selectTokenGroupForUpdate(
            @Param("organizationId") String organizationId,
            @Param("shopId") String shopId);
}
