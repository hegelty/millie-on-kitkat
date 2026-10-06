package me.crema.millietls;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.ExtensionsKt;

/** Reuses the original Kotlin-enabled mapper for EPUB ContentEvent JSON only. */
public final class ContentEventMapper {
    private static ObjectMapper mapper;

    private ContentEventMapper() {}

    public static synchronized ObjectMapper get() {
        if (mapper == null) mapper = ExtensionsKt.jacksonObjectMapper();
        return mapper;
    }
}
