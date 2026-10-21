package ci.fcloud.hub.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 雪花 ID 序列化为字符串。
 *
 * <p>Snowflake 生成的 19 位 ID 超过 JS 的 Number.MAX_SAFE_INTEGER（2^53-1），
 * 以 JSON 数字下发会在前端 JSON.parse 时被静默舍入（...950914 → ...951000），
 * 导致按 id 打开详情、订阅、深链等全部 404。统一按字符串下发。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
