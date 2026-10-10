package com.yingdao.aicommerce.product.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCaptureOriginQueryTest {
    @Test
    void generatedCaptureQueryBindsTenantAndEveryRequestedIdWithoutUserSql() throws Exception {
        var method = ProductCaptureMapper.class.getMethod("selectCommerceGeneratedCaptureIds", String.class, List.class);
        String script = String.join("\n", method.getAnnotation(Select.class).value());
        var source = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class);
        var parameters = Map.of("organizationId", "org-a", "captureIds", List.of("capture-1", "capture-'2"));
        var bound = source.getBoundSql(parameters);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.contains("FROM commerce_batch_publications publication"));
        assertTrue(sql.contains("WHERE publication.organization_id = ? AND publication.capture_id IN"));
        assertEquals(3, bound.getParameterMappings().size());
        assertEquals("organizationId", bound.getParameterMappings().getFirst().getProperty());
        assertEquals("capture-1", bound.getAdditionalParameter(bound.getParameterMappings().get(1).getProperty()));
        assertEquals("capture-'2", bound.getAdditionalParameter(bound.getParameterMappings().get(2).getProperty()));
        assertTrue(!sql.contains("org-a") && !sql.contains("capture-'2"));
    }
}
