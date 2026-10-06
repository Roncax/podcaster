package org.roncax.podcaster.ingestion;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

@ApplicationScoped
public class ConnectorRegistry {
    @Inject Instance<SourceConnector> connectors;

    public Optional<SourceConnector> find(String type) {
        return connectors.stream().filter(c -> c.type().equals(type)).findFirst();
    }

    public SortedSet<String> types() {
        TreeSet<String> types = new TreeSet<>();
        connectors.forEach(c -> types.add(c.type()));
        return types;
    }
}
