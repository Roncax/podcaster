package org.roncax.podcaster.util;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.jackson.ObjectMapperCustomizer;
import jakarta.inject.Singleton;

@Singleton
public class JacksonConfig implements ObjectMapperCustomizer {

    abstract static class PanacheMixin {
        @JsonIgnore abstract boolean isPersistent();
    }

    @Override
    public void customize(ObjectMapper mapper) {
        mapper.addMixIn(PanacheEntityBase.class, PanacheMixin.class);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
