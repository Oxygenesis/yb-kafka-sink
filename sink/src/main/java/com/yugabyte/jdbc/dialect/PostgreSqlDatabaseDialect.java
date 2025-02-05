/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.yugabyte.jdbc.dialect;

import com.yugabyte.jdbc.dialect.DatabaseDialectProvider.SubprotocolBasedProvider;
import com.yugabyte.jdbc.sink.metadata.SinkRecordField;
import com.yugabyte.jdbc.source.ColumnMapping;
import com.yugabyte.jdbc.util.*;
import com.yugabyte.jdbc.util.ExpressionBuilder.Transform;
import java.sql.*;
import java.util.*;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Schema.Type;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A {@link DatabaseDialect} for PostgreSQL. */
public class PostgreSqlDatabaseDialect extends GenericDatabaseDialect {

  private final Logger log = LoggerFactory.getLogger(PostgreSqlDatabaseDialect.class);

  /** The provider for {@link PostgreSqlDatabaseDialect}. */
  public static class Provider extends SubprotocolBasedProvider {
    public Provider() {
      super(PostgreSqlDatabaseDialect.class.getSimpleName(), "postgresql");
    }

    @Override
    public DatabaseDialect create(AbstractConfig config) {
      return new PostgreSqlDatabaseDialect(config);
    }
  }

  static final String JSON_TYPE_NAME = "json";
  static final String JSONB_TYPE_NAME = "jsonb";
  static final String UUID_TYPE_NAME = "uuid";

  /** Define the PG datatypes that require casting upon insert/update statements. */
  private static final Set<String> CAST_TYPES =
      Collections.unmodifiableSet(Utils.mkSet(JSON_TYPE_NAME, JSONB_TYPE_NAME, UUID_TYPE_NAME));

  /**
   * Create a new dialect instance with the given connector configuration.
   *
   * @param config the connector configuration; may not be null
   */
  public PostgreSqlDatabaseDialect(AbstractConfig config) {
    super(config, new IdentifierRules(".", "\"", "\""));
  }

  /**
   * Perform any operations on a {@link PreparedStatement} before it is used. This is called from
   * the {@link #createPreparedStatement(Connection, String)} method after the statement is created
   * but before it is returned/used.
   *
   * <p>This method sets the {@link PreparedStatement#setFetchDirection(int) fetch direction} to
   * {@link ResultSet#FETCH_FORWARD forward} as an optimization for the driver to allow it to scroll
   * more efficiently through the result set and prevent out of memory errors.
   *
   * @param stmt the prepared statement; never null
   * @throws SQLException the error that might result from initialization
   */
  @Override
  protected void initializePreparedStatement(PreparedStatement stmt) throws SQLException {
    super.initializePreparedStatement(stmt);

    log.trace("Initializing PreparedStatement fetch direction to FETCH_FORWARD for '{}'", stmt);
    stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
  }

  @Override
  public String addFieldToSchema(ColumnDefinition columnDefn, SchemaBuilder builder) {
    // Add the PostgreSQL-specific types first
    final String fieldName = fieldNameFor(columnDefn);
    switch (columnDefn.type()) {
      case Types.BIT:
        {
          // PostgreSQL allows variable length bit strings, but when length is 1 then the driver
          // returns a 't' or 'f' string value to represent the boolean value, so we need to handle
          // this as well as lengths larger than 8.
          boolean optional = columnDefn.isOptional();
          int numBits = columnDefn.precision();
          Schema schema;
          if (numBits <= 1) {
            schema = optional ? Schema.OPTIONAL_BOOLEAN_SCHEMA : Schema.BOOLEAN_SCHEMA;
          } else if (numBits <= 8) {
            // For consistency with what the connector did before ...
            schema = optional ? Schema.OPTIONAL_INT8_SCHEMA : Schema.INT8_SCHEMA;
          } else {
            schema = optional ? Schema.OPTIONAL_BYTES_SCHEMA : Schema.BYTES_SCHEMA;
          }
          builder.field(fieldName, schema);
          return fieldName;
        }
      case Types.OTHER:
        {
          // Some of these types will have fixed size, but we drop this from the schema conversion
          // since only fixed byte arrays can have a fixed size
          if (isJsonType(columnDefn)) {
            builder.field(
                fieldName,
                columnDefn.isOptional() ? Schema.OPTIONAL_STRING_SCHEMA : Schema.STRING_SCHEMA);
            return fieldName;
          }

          if (UUID.class.getName().equals(columnDefn.classNameForType())) {
            builder.field(
                fieldName,
                columnDefn.isOptional() ? Schema.OPTIONAL_STRING_SCHEMA : Schema.STRING_SCHEMA);
            return fieldName;
          }

          break;
        }
      default:
        break;
    }

    // Delegate for the remaining logic
    return super.addFieldToSchema(columnDefn, builder);
  }

  @Override
  protected ColumnConverter columnConverterFor(
      ColumnMapping mapping, ColumnDefinition defn, int col, boolean isJdbc4) {
    // First handle any PostgreSQL-specific types
    ColumnDefinition columnDefn = mapping.columnDefn();
    switch (columnDefn.type()) {
      case Types.BIT:
        {
          // PostgreSQL allows variable length bit strings, but when length is 1 then the driver
          // returns a 't' or 'f' string value to represent the boolean value, so we need to handle
          // this as well as lengths larger than 8.
          final int numBits = columnDefn.precision();
          if (numBits <= 1) {
            return rs -> rs.getBoolean(col);
          } else if (numBits <= 8) {
            // Do this for consistency with earlier versions of the connector
            return rs -> rs.getByte(col);
          }
          return rs -> rs.getBytes(col);
        }
      case Types.OTHER:
        {
          if (isJsonType(columnDefn)) {
            return rs -> rs.getString(col);
          }

          if (UUID.class.getName().equals(columnDefn.classNameForType())) {
            return rs -> rs.getString(col);
          }
          break;
        }
      default:
        break;
    }

    // Delegate for the remaining logic
    return super.columnConverterFor(mapping, defn, col, isJdbc4);
  }

  protected boolean isJsonType(ColumnDefinition columnDefn) {
    String typeName = columnDefn.typeName();
    return JSON_TYPE_NAME.equalsIgnoreCase(typeName) || JSONB_TYPE_NAME.equalsIgnoreCase(typeName);
  }

  @Override
  protected String getSqlType(SinkRecordField field) {
    if (field.schemaName() != null) {
      switch (field.schemaName()) {
        case Decimal.LOGICAL_NAME:
          return "DECIMAL";
        case Date.LOGICAL_NAME:
          return "DATE";
        case Time.LOGICAL_NAME:
          return "TIME";
        case Timestamp.LOGICAL_NAME:
          return "TIMESTAMP";
        default:
          // fall through to normal types
      }
    }
    switch (field.schemaType()) {
      case INT8:
        return "SMALLINT";
      case INT16:
        return "SMALLINT";
      case INT32:
        return "INT";
      case INT64:
        return "BIGINT";
      case FLOAT32:
        return "REAL";
      case FLOAT64:
        return "DOUBLE PRECISION";
      case BOOLEAN:
        return "BOOLEAN";
      case STRING:
        return "TEXT";
      case BYTES:
        return "BYTEA";
      default:
        return super.getSqlType(field);
    }
  }

  @Override
  public String buildInsertStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns,
      TableDefinition definition) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("INSERT INTO ");
    builder.append(table);
    builder.append(" (");
    builder
        .appendList()
        .delimitedBy(",")
        .transformedBy(ExpressionBuilder.columnNames())
        .of(keyColumns, nonKeyColumns);
    builder.append(") VALUES (");
    builder
        .appendList()
        .delimitedBy(",")
        .transformedBy(this.columnValueVariables(definition))
        .of(keyColumns, nonKeyColumns);
    builder.append(")");
    return builder.toString();
  }

  @Override
  public String buildUpdateStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns,
      TableDefinition definition) {
    ExpressionBuilder builder = expressionBuilder();
    builder.append("UPDATE ");
    builder.append(table);
    builder.append(" SET ");
    builder
        .appendList()
        .delimitedBy(", ")
        .transformedBy(this.columnNamesWithValueVariables(definition))
        .of(nonKeyColumns);
    if (!keyColumns.isEmpty()) {
      builder.append(" WHERE ");
      builder
          .appendList()
          .delimitedBy(" AND ")
          .transformedBy(ExpressionBuilder.columnNamesWith(" = ?"))
          .of(keyColumns);
    }
    return builder.toString();
  }

  @Override
  public String buildUpsertQueryStatement(
      TableId table,
      Collection<ColumnId> keyColumns,
      Collection<ColumnId> nonKeyColumns,
      TableDefinition definition) {
    final Transform<ColumnId> transform =
        (builder, col) -> {
          builder.appendColumnName(col.name()).append("=EXCLUDED.").appendColumnName(col.name());
        };

    ExpressionBuilder builder = expressionBuilder();
    builder.append("INSERT INTO ");
    builder.append(table);
    builder.append(" (");
    builder
        .appendList()
        .delimitedBy(",")
        .transformedBy(ExpressionBuilder.columnNames())
        .of(keyColumns, nonKeyColumns);
    builder.append(") VALUES (");
    builder
        .appendList()
        .delimitedBy(",")
        .transformedBy(this.columnValueVariables(definition))
        .of(keyColumns, nonKeyColumns);
    builder.append(") ON CONFLICT (");
    builder
        .appendList()
        .delimitedBy(",")
        .transformedBy(ExpressionBuilder.columnNames())
        .of(keyColumns);
    if (nonKeyColumns.isEmpty()) {
      builder.append(") DO NOTHING");
    } else {
      builder.append(") DO UPDATE SET ");
      builder.appendList().delimitedBy(",").transformedBy(transform).of(nonKeyColumns);
    }
    return builder.toString();
  }

  @Override
  protected void formatColumnValue(
      ExpressionBuilder builder,
      String schemaName,
      Map<String, String> schemaParameters,
      Type type,
      Object value) {
    if (schemaName == null && Type.BOOLEAN.equals(type)) {
      builder.append((Boolean) value ? "TRUE" : "FALSE");
    } else {
      super.formatColumnValue(builder, schemaName, schemaParameters, type, value);
    }
  }

  /**
   * Return the transform that produces an assignment expression each with the name of one of the
   * columns and the prepared statement variable. PostgreSQL may require the variable to have a type
   * suffix, such as {@code ?::uuid}.
   *
   * @param defn the table definition; may be null if unknown
   * @return the transform that produces the assignment expression for use within a prepared
   *     statement; never null
   */
  protected Transform<ColumnId> columnNamesWithValueVariables(TableDefinition defn) {
    return (builder, columnId) -> {
      builder.appendColumnName(columnId.name());
      builder.append(" = ?");
      builder.append(valueTypeCast(defn, columnId));
    };
  }

  /**
   * Return the transform that produces a prepared statement variable for each of the columns.
   * PostgreSQL may require the variable to have a type suffix, such as {@code ?::uuid}.
   *
   * @param defn the table definition; may be null if unknown
   * @return the transform that produces the variable expression for each column; never null
   */
  protected Transform<ColumnId> columnValueVariables(TableDefinition defn) {
    return (builder, columnId) -> {
      builder.append("?");
      builder.append(valueTypeCast(defn, columnId));
    };
  }

  /**
   * Return the typecast expression that can be used as a suffix for a value variable of the given
   * column in the defined table.
   *
   * <p>This method returns a blank string except for those column types that require casting when
   * set with literal values. For example, a column of type {@code uuid} must be cast when being
   * bound with with a {@code varchar} literal, since a UUID value cannot be bound directly.
   *
   * @param tableDefn the table definition; may be null if unknown
   * @param columnId the column within the table; may not be null
   * @return the cast expression, or an empty string; never null
   */
  protected String valueTypeCast(TableDefinition tableDefn, ColumnId columnId) {
    if (tableDefn != null) {
      ColumnDefinition defn = tableDefn.definitionForColumn(columnId.name());
      if (defn != null) {
        String typeName = defn.typeName(); // database-specific
        if (typeName != null) {
          typeName = typeName.toLowerCase();
          if (CAST_TYPES.contains(typeName)) {
            return "::" + typeName;
          }
        }
      }
    }
    return "";
  }
}
