package ru.curs.celesta.dbutils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import ru.curs.celesta.CelestaException;
import ru.curs.celesta.score.BinaryColumn;
import ru.curs.celesta.score.BooleanColumn;
import ru.curs.celesta.score.Column;
import ru.curs.celesta.score.DateTimeColumn;
import ru.curs.celesta.score.FloatingColumn;
import ru.curs.celesta.score.Grain;
import ru.curs.celesta.score.IntegerColumn;
import ru.curs.celesta.score.StringColumn;
import ru.curs.celesta.score.Table;

/**
 * Адаптер MSSQL.
 * 
 */
final class MSSQLAdaptor extends DBAdaptor {

	private static final Map<Class<? extends Column>, ColumnDefiner> TYPES_DICT = new HashMap<>();
	static {
		TYPES_DICT.put(IntegerColumn.class, new ColumnDefiner() {
			@Override
			String dbFieldType() {
				return "int";
			}

			@Override
			String getColumnDef(Column c) {
				IntegerColumn ic = (IntegerColumn) c;
				String defaultStr = "";
				if (ic.isIdentity()) {
					defaultStr = "IDENTITY";
				} else if (ic.getDefaultValue() != null) {
					defaultStr = DEFAULT + ic.getDefaultValue();
				}
				return join(c.getName(), dbFieldType(), nullable(c), defaultStr);
			}
		});

		TYPES_DICT.put(FloatingColumn.class, new ColumnDefiner() {

			@Override
			String dbFieldType() {
				return "real";
			}

			@Override
			String getColumnDef(Column c) {
				FloatingColumn ic = (FloatingColumn) c;
				String defaultStr = "";
				if (ic.getDefaultvalue() != null) {
					defaultStr = DEFAULT + ic.getDefaultvalue();
				}
				return join(c.getName(), dbFieldType(), nullable(c), defaultStr);
			}
		});

		TYPES_DICT.put(StringColumn.class, new ColumnDefiner() {

			@Override
			String dbFieldType() {
				return "nvarchar";
			}

			@Override
			String getColumnDef(Column c) {
				StringColumn ic = (StringColumn) c;
				String fieldType = String.format("%s(%s)", dbFieldType(),
						ic.isMax() ? "max" : ic.getLength());
				String defaultStr = "";
				if (ic.getDefaultValue() != null) {
					defaultStr = DEFAULT
							+ StringColumn.quoteString(ic.getDefaultValue());
				}
				return join(c.getName(), fieldType, nullable(c), defaultStr);
			}
		});

		TYPES_DICT.put(BinaryColumn.class, new ColumnDefiner() {

			@Override
			String dbFieldType() {
				return "image";
			}

			@Override
			String getColumnDef(Column c) {
				BinaryColumn ic = (BinaryColumn) c;
				String defaultStr = "";
				if (ic.getDefaultValue() != null) {
					defaultStr = DEFAULT + ic.getDefaultValue();
				}
				return join(c.getName(), dbFieldType(), nullable(c), defaultStr);
			}
		});

		TYPES_DICT.put(DateTimeColumn.class, new ColumnDefiner() {

			@Override
			String dbFieldType() {
				return "datetime";
			}

			@Override
			String getColumnDef(Column c) {
				DateTimeColumn ic = (DateTimeColumn) c;
				String defaultStr = "";
				if (ic.isGetdate()) {
					defaultStr = DEFAULT + "getdate()";
				} else if (ic.getDefaultValue() != null) {
					defaultStr = DEFAULT + ic.getDefaultValue();
				}
				return join(c.getName(), dbFieldType(), nullable(c), defaultStr);
			}
		});

		TYPES_DICT.put(BooleanColumn.class, new ColumnDefiner() {

			@Override
			String dbFieldType() {
				return "bit";
			}

			@Override
			String getColumnDef(Column c) {
				BooleanColumn ic = (BooleanColumn) c;
				String defaultStr = "";
				if (ic.getDefaultValue() != null) {
					defaultStr = DEFAULT + "'" + ic.getDefaultValue() + "'";
				}
				return join(c.getName(), dbFieldType(), nullable(c), defaultStr);
			}
		});
	}

	@Override
	boolean tableExists(Connection conn, String schema, String name)
			throws SQLException {
		PreparedStatement check = conn.prepareStatement(String.format(
				"select coalesce(object_id('%s.%s'), -1)", schema, name));
		ResultSet rs = check.executeQuery();
		try {
			rs.next();
			return rs.getInt(1) != -1;
		} finally {
			rs.close();
			check.close();
		}
	}

	@Override
	boolean userTablesExist(Connection conn) throws SQLException {
		PreparedStatement check = conn
				.prepareStatement("select count(*) from sys.tables;");
		ResultSet rs = check.executeQuery();
		try {
			rs.next();
			return rs.getInt(1) != 0;
		} finally {
			rs.close();
			check.close();
		}
	}

	@Override
	void createSchemaIfNotExists(Connection conn, String name)
			throws SQLException {
		PreparedStatement check = conn.prepareStatement(String.format(
				"select coalesce(SCHEMA_ID('%s'), -1)", name));
		ResultSet rs = check.executeQuery();
		try {
			rs.next();
			if (rs.getInt(1) == -1) {
				PreparedStatement create = conn.prepareStatement(String.format(
						"create schema %s;", name));
				create.execute();
				create.close();
			}
		} finally {
			rs.close();
			check.close();
		}
	}

	@Override
	ColumnDefiner getColumnDefiner(Column c) {
		return TYPES_DICT.get(c.getClass());
	}

	@Override
	PreparedStatement getOneFieldStatement(Connection conn, Column c)
			throws CelestaException {
		Table t = c.getParentTable();
		String sql = String.format("select top 1 %s from %s.%s where %s;",
				c.getName(), t.getGrain().getName(), t.getName(),
				getRecordWhereClause(t));
		return prepareStatement(conn, sql);
	}

	@Override
	PreparedStatement getOneRecordStatement(Connection conn, Table t)
			throws CelestaException {
		String sql = String.format("select top 1 %s from %s.%s where %s;",
				getTableFieldsListExceptBLOBs(t), t.getGrain().getName(),
				t.getName(), getRecordWhereClause(t));
		return prepareStatement(conn, sql);
	}

	@Override
	PreparedStatement getRecordSetStatement(Connection conn, Table t,
			Map<String, AbstractFilter> filters, List<String> orderBy)
			throws CelestaException {

		// Готовим условие where
		StringBuilder whereClause = setWhereClause(filters);

		// Соединяем полученные компоненты в стандартный запрос
		// SELECT..FROM..WHERE..ORDER BY
		String sql = getSelectFromOrderBy(t, whereClause.toString(), orderBy);

		try {
			PreparedStatement result = conn.prepareStatement(sql);
			// А теперь заполняем параметры
			fillSetQueryParameters(filters, result);
			return result;
		} catch (SQLException e) {
			throw new CelestaException(e.getMessage());
		}
	}

	private void fillSetQueryParameters(Map<String, AbstractFilter> filters,
			PreparedStatement result) throws CelestaException {
		int i = 1;
		for (AbstractFilter f : filters.values()) {
			if (f instanceof SingleValue) {
				setParam(result, i, ((SingleValue) f).getValue());
				i++;
			} else if (f instanceof Range) {
				setParam(result, i, ((Range) f).getValueFrom());
				i++;
				setParam(result, i, ((Range) f).getValueTo());
				i++;
			} else if (f instanceof Filter)
				throw new RuntimeException("not implemented yet");
		}
	}

	private StringBuilder setWhereClause(Map<String, AbstractFilter> filters) {
		if (filters == null)
			throw new IllegalArgumentException();
		StringBuilder whereClause = new StringBuilder();
		for (Entry<String, AbstractFilter> e : filters.entrySet()) {
			if (whereClause.length() > 0)
				whereClause.append(" and ");
			if (e.getValue() instanceof SingleValue)
				whereClause.append(String.format("(%s = ?)", e.getKey()));
			else if (e.getValue() instanceof Range)
				whereClause.append(String.format("(%s between ? and ?)",
						e.getKey()));
			else if (e.getValue() instanceof Filter)
				throw new RuntimeException("not implemented yet");
		}
		return whereClause;
	}

	@Override
	PreparedStatement getInsertRecordStatement(Connection conn, Table t,
			boolean[] nullsMask) throws CelestaException {

		Iterator<String> columns = t.getColumns().keySet().iterator();
		// Создаём параметризуемую часть запроса, пропуская нулевые значения.
		StringBuilder fields = new StringBuilder();
		StringBuilder params = new StringBuilder();
		for (int i = 0; i < t.getColumns().size(); i++) {
			String c = columns.next();
			if (nullsMask[i])
				continue;
			if (params.length() > 0) {
				fields.append(", ");
				params.append(", ");
			}
			params.append("?");
			fields.append(c);
		}

		String sql = String.format("insert %s.%s (%s) values (%s);", t
				.getGrain().getName(), t.getName(), fields.toString(), params
				.toString());
		return prepareStatement(conn, sql);
	}

	@Override
	PreparedStatement getUpdateRecordStatement(Connection conn, Table t)
			throws CelestaException {
		StringBuilder setClause = new StringBuilder();
		for (String c : t.getColumns().keySet())
			// Пропускаем ключевые поля
			if (!t.getPrimaryKey().containsKey(c)) {
				if (setClause.length() > 0)
					setClause.append(", ");
				setClause.append(String.format("%s = ?", c));
			}

		String sql = String.format("update " + tableTemplate()
				+ " set %s where %s", t.getGrain().getName(), t.getName(),
				setClause.toString(), getRecordWhereClause(t));
		return prepareStatement(conn, sql);
	}

	@Override
	PreparedStatement getDeleteRecordStatement(Connection conn, Table t)
			throws CelestaException {
		String sql = String.format("delete %s.%s where %s;", t.getGrain()
				.getName(), t.getName(), getRecordWhereClause(t));
		return prepareStatement(conn, sql);
	}

	@Override
	public Set<String> getIndices(Connection conn, Grain g)
			throws CelestaException {
		String sql = String.format(
				"select name from sys.indexes where object_id in ("
						+ "select object_id from sys.tables "
						+ "where sys.tables.schema_id = SCHEMA_ID('%s')) "
						+ "and name is not null;", g.getName());
		return sqlToStringSet(conn, sql);
	}

	@Override
	public Set<String> getColumns(Connection conn, Table t)
			throws CelestaException {
		String sql = String
				.format("select name from sys.columns where object_id = OBJECT_ID('%s.%s');",
						t.getGrain().getName(), t.getName());
		return sqlToStringSet(conn, sql);
	}

	@Override
	PreparedStatement deleteRecordSetStatement(Connection conn, Table t,
			Map<String, AbstractFilter> filters) throws CelestaException {
		// Готовим условие where
		StringBuilder whereClause = setWhereClause(filters);

		// Готовим запрос на удаление
		String sql = String.format("delete %s.%s %s;", t.getGrain().getName(),
				t.getName(), whereClause.length() > 0 ? "where " + whereClause
						: "");
		try {
			PreparedStatement result = conn.prepareStatement(sql);
			// А теперь заполняем параметры
			fillSetQueryParameters(filters, result);
			return result;
		} catch (SQLException e) {
			throw new CelestaException(e.getMessage());
		}
	}

	@Override
	int getCurrentIdent(Connection conn, Table t) throws CelestaException {
		PreparedStatement stmt = prepareStatement(conn, String.format(
				"SELECT IDENT_CURRENT('%s.%s')", t.getGrain().getName(),
				t.getName()));
		try {
			ResultSet rs = stmt.executeQuery();
			rs.next();
			return rs.getInt(1);
		} catch (SQLException e) {
			throw new CelestaException(e.getMessage());
		}

	}
}