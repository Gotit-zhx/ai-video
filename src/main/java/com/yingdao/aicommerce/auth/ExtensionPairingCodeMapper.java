package com.yingdao.aicommerce.auth;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

@Mapper
public interface ExtensionPairingCodeMapper extends BaseMapper<ExtensionPairingCode> {
    @Select("""
            SELECT * FROM extension_pairing_codes
            WHERE code_hash = #{codeHash}
              AND consumed_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > #{now}
            LIMIT 1 FOR UPDATE
            """)
    ExtensionPairingCode selectUsableForUpdate(
            @Param("codeHash") String codeHash,
            @Param("now") Instant now
    );
}
