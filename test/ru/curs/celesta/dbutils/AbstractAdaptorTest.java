package ru.curs.celesta.dbutils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import ru.curs.celesta.CelestaException;
import ru.curs.celesta.ConnectionPool;
import ru.curs.celesta.score.Column;
import ru.curs.celesta.score.IntegerColumn;
import ru.curs.celesta.score.Score;
import ru.curs.celesta.score.Table;

public abstract class AbstractAdaptorTest {
	final static String GRAIN_NAME = "gtest";
	final static String SCORE_NAME = "testScore";
	private DBAdaptor dba;
	private Score score;

	@Before
	public void setup() throws Exception {
		Connection conn = ConnectionPool.get();
		try {
			dba.createSchemaIfNotExists(conn, GRAIN_NAME);
		} finally {
			ConnectionPool.putBack(conn);
		}
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		try {
			dba.dropTables(t);
		} catch (Exception e) {
		}
	}

	@Test
	public void tableExists() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		try {
			boolean result = dba.tableExists(t.getGrain().getName(),
					t.getName());
			assertTrue(result);
		} finally {
			dba.dropTables(t);
		}
	}

	@Test
	public void userTablesExist() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		try {
			boolean result = dba.userTablesExist();
			assertTrue(result);
		} finally {
			dba.dropTables(t);
		}
	}

	@Test
	public void createTable() throws CelestaException {
		try {
			Table t = score.getGrain(GRAIN_NAME).getTable("test");
			dba.createTable(t);
			dba.dropTables(t);
		} catch (Exception ex) {
			fail("Threw Exception:" + ex.getMessage());
		}
	}

	private int insertRow(Connection conn, Table t, Object val)
			throws Exception {
		int count = t.getColumns().size();

		boolean[] nullsMask = new boolean[count];
		int i = 0;
		for (Column c : t.getColumns().values()) {
			nullsMask[i] = (c instanceof IntegerColumn)
					&& ((IntegerColumn) c).isIdentity();
			i++;
		}

		PreparedStatement pstmt = dba.getInsertRecordStatement(conn, t,
				nullsMask);
		assertNotNull(pstmt);
		i = 1;
		for (boolean n : nullsMask)
			if (!n) {
				DBAdaptor.setParam(pstmt, i, val);
				i++;
			}

		try {
			int rowCount = pstmt.executeUpdate();
			return rowCount;
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void getInsertRecordStatement() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			int rowCount = insertRow(conn, t, 1);
			assertTrue(rowCount == 1);
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	@Test
	public void getUpdateRecordStatement() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			insertRow(conn, t, 1);
			int count = t.getColumns().size();
			PreparedStatement pstmt = dba.getUpdateRecordStatement(conn, t);
			assertNotNull(pstmt);
			for (int i = 2; i <= count; i++) {
				DBAdaptor.setParam(pstmt, i - 1, 2);
			}
			DBAdaptor.setParam(pstmt, count, 1);// key value
			int rowCount = pstmt.executeUpdate();
			assertTrue(rowCount == 1);
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	@Test
	public void getDeleteRecordStatement() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			insertRow(conn, t, 1);
			PreparedStatement pstmt = dba.getDeleteRecordStatement(conn, t);
			assertNotNull(pstmt);
			DBAdaptor.setParam(pstmt, 1, 1);// key value
			int rowCount = pstmt.executeUpdate();
			assertTrue(rowCount == 1);
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	@Test
	public void getColumns() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			Set<String> columnSet = dba.getColumns(conn, t);
			assertNotNull(columnSet);
			assertTrue(columnSet.size() != 0);
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	@Test
	public void getIndices() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			Set<String> indicesSet = dba.getIndices(conn, t.getGrain());
			assertNotNull(indicesSet);
			assertTrue(indicesSet.size() != 0);
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	@Test
	public void getOneRecordStatement() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			insertRow(conn, t, 1);
			PreparedStatement pstmt = dba.getOneRecordStatement(conn, t);
			assertNotNull(pstmt);
			DBAdaptor.setParam(pstmt, 1, 1);// key value
			ResultSet rs = pstmt.executeQuery();
			assertTrue(rs.next());
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	@Test
	public void deleteRecordSetStatement() throws Exception {
		Table t = score.getGrain(GRAIN_NAME).getTable("test");
		dba.createTable(t);
		Connection conn = ConnectionPool.get();
		try {
			insertRow(conn, t, 1);
			PreparedStatement pstmt = dba.deleteRecordSetStatement(conn, t,
					Collections.<String, AbstractFilter> emptyMap());
			assertNotNull(pstmt);
			int rowCount = pstmt.executeUpdate();
			assertTrue(rowCount == 1);
		} finally {
			ConnectionPool.putBack(conn);
			dba.dropTables(t);
		}
	}

	protected void setDba(DBAdaptor dba) {
		this.dba = dba;
	}

	protected void setScore(Score score) {
		this.score = score;
	}
}