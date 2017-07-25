package ru.curs.celesta.dbutils;

import ru.curs.celesta.CelestaException;
import ru.curs.celesta.dbutils.adaptors.DBAdaptor;
import ru.curs.celesta.dbutils.stmt.ParameterSetter;
import ru.curs.celesta.dbutils.stmt.PreparedStmtHolder;
import ru.curs.celesta.dbutils.term.WhereTerm;
import ru.curs.celesta.dbutils.term.WhereTermsMaker;
import ru.curs.celesta.score.GrainElement;
import ru.curs.celesta.score.TableElement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Created by ioann on 06.07.2017.
 */
class CursorGetHelper {

  @FunctionalInterface
  interface ParseResultFunction {
    void apply(ResultSet rs) throws SQLException;
  }

  @FunctionalInterface
  interface InitXRecFunction {
    void apply() throws CelestaException;
  }

  private final DBAdaptor db;
  private final Connection conn;
  private final TableElement meta;
  private final String tableName;
  private final List<String> fields;

  private final PreparedStmtHolder get = new PreparedStmtHolder() {
    @Override
    protected PreparedStatement initStatement(List<ParameterSetter> program) throws CelestaException {
      WhereTerm where = WhereTermsMaker.getPKWhereTermForGet(meta);
      where.programParams(program);
      return db.getOneRecordStatement(conn, meta, where.getWhere());
    }
  };

  public CursorGetHelper(DBAdaptor db, Connection conn, TableElement meta,
                         String tableName, List<String> fields) {
    this.db = db;
    this.conn = conn;
    this.meta = meta;
    this.tableName = tableName;
    this.fields = fields;
  }


  PreparedStmtHolder getHolder() {
    return get;
  }


  final boolean internalGet(ParseResultFunction parseResultFunc, InitXRecFunction initXRecFunc,
                            int recversion, Object... values) throws CelestaException {
    PreparedStatement g = prepareGet(recversion, values);
    boolean result = false;
    try {
      ResultSet rs = g.executeQuery();
      try {
        result = rs.next();
        if (result) {
          parseResultFunc.apply(rs);
          initXRecFunc.apply();
        }
      } finally {
        rs.close();
      }
    } catch (SQLException e) {
      throw new CelestaException(e.getMessage());
    }
    return result;
  }


  final PreparedStatement prepareGet(int recversion, Object... values) throws CelestaException {
    if (meta.getPrimaryKey().size() != values.length)
      throw new CelestaException("Invalid number of 'get' arguments for '%s': expected %d, provided %d.",
          tableName, meta.getPrimaryKey().size(), values.length);
    PreparedStatement result = get.getStatement(values, recversion);
    return result;
  }


  static class CursorGetHelperBuilder {
    private DBAdaptor db;
    private Connection conn;
    private TableElement meta;
    private String tableName;
    private List<String> fields = Collections.emptyList();

    CursorGetHelperBuilder withDb(DBAdaptor db) {
      this.db = db;
      return this;
    }

    CursorGetHelperBuilder withConn(Connection conn) {
      this.conn = conn;
      return this;
    }

    CursorGetHelperBuilder withMeta(TableElement meta) {
      this.meta = meta;
      return this;
    }

    CursorGetHelperBuilder withTableName(String tableName) {
      this.tableName = tableName;
      return this;
    }

    CursorGetHelperBuilder withFields(List<String> fields) {
      if (!fields.isEmpty())
        this.fields = fields;
      return this;
    }

    CursorGetHelper build() {
      return new CursorGetHelper(db, conn, meta, tableName, fields);
    }
  }
}
