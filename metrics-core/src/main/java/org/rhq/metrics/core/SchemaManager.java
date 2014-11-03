package org.rhq.metrics.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Map;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.rhq.metrics.util.TokenReplacingReader;

/**
 * @author John Sanda
 * @author Heiko W. Rupp
 */
public class SchemaManager {

    private static final Logger logger = LoggerFactory.getLogger(SchemaManager.class);

    private Session session;

    public SchemaManager(Session session) {
        this.session = session;
    }

    public void createSchema(String keyspace) throws IOException {
        logger.info("Creating schema for keyspace " + keyspace);

        ResultSet resultSet = session.execute("SELECT * FROM system.schema_keyspaces WHERE keyspace_name = '" +
            keyspace + "'");
        if (!resultSet.isExhausted()) {
            logger.info("Schema already exist. Skipping schema creation.");
            return;
        }

        ImmutableMap<String, String> schemaVars = ImmutableMap.of("keyspace", keyspace);

        InputStream inputStream = getClass().getResourceAsStream("/schema.cql");
        InputStreamReader reader = new InputStreamReader(inputStream);
        String content = CharStreams.toString(reader);

        for (String cql : content.split("(?m)^-- #.*$")) {
            if (!cql.startsWith("--")) {
                TokenReplacingReader tokenReader = new TokenReplacingReader(cql.trim(), schemaVars);
                String updatedCQL = substituteVars(cql.trim(), schemaVars);
                logger.info("Executing CQL:\n" + updatedCQL + "\n");
                session.execute(updatedCQL);
            }
        }
    }

    private String substituteVars(String cql, Map<String, String> vars) {
        TokenReplacingReader reader = new TokenReplacingReader(cql, vars);
        StringWriter writer = new StringWriter();
        try {
            char[] buffer = new char[32768];
            int cnt;
            while ((cnt = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, cnt);
            }
            return writer.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to perform variable substition on CQL", e);
        } finally {
            try {
                Closeables.close(reader, true);
                Closeables.close(writer, true);
            } catch (IOException e) {
                logger.info("There was a problem closing resources", e);
            }
        }
    }

    public void updateSchema(String keyspace) {

        keyspace = keyspace.toLowerCase();

        ResultSet resultSet = session.execute(
             "SELECT * FROM system.schema_keyspaces WHERE keyspace_name = '" + keyspace + "'");


        if (resultSet.isExhausted()) {
            // No keyspace found - start from scratch
            session.execute(
                "CREATE KEYSPACE " + keyspace + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        }
        // We have a keyspace - now use this for the session.
        Session session2 = session.getCluster().connect(keyspace);

        session2.execute(
            "CREATE TABLE IF NOT EXISTS metrics ( " +
                "bucket text, " +
                "metric_id text, " +
                "time timestamp, " +
                "value map<int, double>, " +
                // ( bucket, metric_id ) are a composite partition key
                "PRIMARY KEY ( (bucket, metric_id) , time) " +
            ")"
        );

        session2.execute(
            "CREATE TABLE IF NOT EXISTS counters ( " +
                "group text, " +
                "c_name text, " +
                "c_value counter, " +
                "PRIMARY KEY (group, c_name) " +
            ")"
        );
    }
}
