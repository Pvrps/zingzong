package io.capture.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A JDBC statement executed through a data source, as it crossed the server's JDBC wrapper:
 * which data source, the SQL as prepared, the bound parameters, what came back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SqlInfo {
    /** The data source (connection pool) name. */
    public String dataSource;
    /** Its JNDI names, when the server could tell. */
    public List<String> jndiNames;
    /** JDBC URL and database product, when the server could tell. */
    public String url;
    public String database;
    /** statement, prepared or callable. */
    public String statementType;
    /** execute, executeQuery, executeUpdate, executeBatch, ... */
    public String operation;
    public String sql;
    /** Bound parameters by position (index 1 is element 0; a position never set is null). */
    public List<JsonNode> parameters;
    /** For executeBatch: the parameter sets added with addBatch, in order. */
    public List<List<JsonNode>> batches;
    /** executeUpdate's count, or executeBatch's counts. */
    public Integer updateCount;
    public int[] updateCounts;
    /** True when the statement produced a result set; rows is how many rows the application read from it. */
    public Boolean resultSet;
    public Long rows;
    /**
     * The values the application read from the result set, row by row, keyed by the column it
     * asked for (its label, or "#n" for an index): what crossed the boundary, without the agent
     * reading the cursor itself. Rows past the element ceiling are counted in rowsNotRecorded.
     */
    public List<java.util.Map<String, JsonNode>> data;
    public Integer rowsNotRecorded;
}
