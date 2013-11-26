package ru.curs.celesta.dbutils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import ru.curs.celesta.CallContext;
import ru.curs.celesta.CelestaException;
import ru.curs.celesta.ConnectionPool;
import ru.curs.celesta.score.Column;
import ru.curs.celesta.score.Grain;
import ru.curs.celesta.score.Index;
import ru.curs.celesta.score.ParseException;
import ru.curs.celesta.score.Score;
import ru.curs.celesta.score.Table;
import ru.curs.celesta.score.VersionString;
import ru.curs.celesta.syscursors.GrainsCursor;
import ru.curs.celesta.syscursors.TablesCursor;

/**
 * Класс, выполняющий процедуру обновления базы данных.
 * 
 */
public final class DBUpdator {

	private static DBAdaptor dba;
	private static GrainsCursor grain;
	private static TablesCursor table;

	private static final Comparator<Grain> GRAIN_COMPARATOR = new Comparator<Grain>() {
		@Override
		public int compare(Grain o1, Grain o2) {
			return o1.getDependencyOrder() - o2.getDependencyOrder();
		}
	};

	private static final Set<Integer> EXPECTED_STATUSES;
	static {
		EXPECTED_STATUSES = new HashSet<>();
		EXPECTED_STATUSES.add(GrainsCursor.READY);
		EXPECTED_STATUSES.add(GrainsCursor.RECOVER);
		EXPECTED_STATUSES.add(GrainsCursor.LOCK);
	}

	private DBUpdator() {
	}

	/**
	 * Буфер для хранения информации о грануле.
	 */
	private static class GrainInfo {
		private boolean recover;
		private boolean lock;
		private int length;
		private int checksum;
		private VersionString version;
	}

	/**
	 * Выполняет обновление структуры БД на основе разобранной объектной модели.
	 * 
	 * @param score
	 *            модель
	 * @throws CelestaException
	 *             в случае ошибки обновления.
	 */
	public static void updateDB(Score score) throws CelestaException {
		if (dba == null)
			dba = DBAdaptor.getAdaptor();
		Connection conn = ConnectionPool.get();
		CallContext context = new CallContext(conn, Cursor.SYSTEMUSERID);
		try {
			grain = new GrainsCursor(context);
			table = new TablesCursor(context);

			// Проверяем наличие главной системной таблицы.
			if (!dba.tableExists("celesta", "grains")) {
				// Если главной таблицы нет, а другие таблицы есть -- ошибка.
				if (dba.userTablesExist())
					throw new CelestaException(
							"No celesta.grains table found in non-empty database.");
				// Если база вообще пустая, то создаём системные таблицы.
				try {
					Grain sys = score.getGrain("celesta");
					dba.createSchemaIfNotExists("celesta");
					dba.createTable(sys.getTable("grains"));
					dba.createTable(sys.getTable("tables"));
					dba.createTable(sys.getTable("logsetup"));
					dba.createTable(sys.getTable("sequences"));
					insertGrainRec(sys);
					updateGrain(sys);
				} catch (ParseException e) {
					throw new CelestaException(
							"No 'celesta' grain definition found.");
				}

			}

			// Теперь собираем в память информацию о гранулах на основании того,
			// что
			// хранится в таблице grains.
			Map<String, GrainInfo> dbGrains = new HashMap<>();
			while (grain.next()) {

				if (!(EXPECTED_STATUSES.contains(grain.getState())))
					throw new CelestaException(
							"Cannot proceed with database upgrade: there are grains "
									+ "not in 'ready', 'recover' or 'lock' state.");
				GrainInfo gi = new GrainInfo();
				gi.checksum = (int) Long.parseLong(grain.getChecksum(), 16);
				gi.length = grain.getLength();
				gi.recover = grain.getState() == GrainsCursor.RECOVER;
				gi.lock = grain.getState() == GrainsCursor.LOCK;
				try {
					gi.version = new VersionString(grain.getVersion());
				} catch (ParseException e) {
					throw new CelestaException(String.format(
							"Error while scanning celesta.grains table: %s",
							e.getMessage()));
				}
				dbGrains.put(grain.getId(), gi);
			}

			// Получаем список гранул на основе метамодели и сортируем его по
			// порядку зависимости.
			List<Grain> grains = new ArrayList<>(score.getGrains().values());
			Collections.sort(grains, GRAIN_COMPARATOR);

			// Выполняем итерацию по гранулам.
			boolean success = true;
			for (Grain g : grains) {
				// Запись о грануле есть?
				GrainInfo gi = dbGrains.get(g.getName());
				if (gi == null) {
					insertGrainRec(g);
					success = updateGrain(g) & success;
				} else {
					// Запись есть -- решение об апгрейде принимается на основе
					// версии и контрольной суммы.
					success = decideToUpgrade(g, gi) & success;
				}
			}
			if (!success)
				throw new CelestaException(
						"Not all grains were updated successfully, see celesta.grains table data for details.");
		} finally {
			ConnectionPool.putBack(conn);
		}
	}

	private static void insertGrainRec(Grain g) throws CelestaException {
		grain.init();
		grain.setId(g.getName());
		grain.setVersion(g.getVersion().toString());
		grain.setLength(g.getLength());
		grain.setChecksum(String.format("%08X", g.getChecksum()));
		grain.setState(GrainsCursor.RECOVER);
		grain.setLastmodified(new Date());
		grain.setMessage("");
		grain.insert();
	}

	private static boolean decideToUpgrade(Grain g, GrainInfo gi)
			throws CelestaException {
		if (gi.lock)
			return true;

		if (gi.recover)
			return updateGrain(g);

		// Как соотносятся версии?
		switch (g.getVersion().compareTo(gi.version)) {
		case LOWER:
			// Старая версия -- не апгрейдим, ошибка.
			throw new CelestaException(
					"Grain '%s' version '%s' is lower than database "
							+ "grain version '%s'. Will not proceed with auto-upgrade.",
					g.getName(), g.getVersion().toString(), gi.version
							.toString());
		case INCONSISTENT:
			// Непонятная (несовместимая) версия -- не апгрейдим,
			// ошибка.
			throw new CelestaException(
					"Grain '%s' version '%s' is inconsistent with database "
							+ "grain version '%s'. Will not proceed with auto-upgrade.",
					g.getName(), g.getVersion().toString(), gi.version
							.toString());
		case GREATER:
			// Версия выросла -- апгрейдим.
			return updateGrain(g);
		case EQUALS:
			// Версия не изменилась: апгрейдим лишь в том случае, если
			// изменилась контрольная сумма.
			if (gi.length != g.getLength() || gi.checksum != g.getChecksum())
				return updateGrain(g);
		default:
			return true;
		}
	}

	/**
	 * Выполняет обновление на уровне отдельной гранулы.
	 * 
	 * @param g
	 *            Гранула.
	 * @throws CelestaException
	 *             в случае ошибки обновления.
	 */
	private static boolean updateGrain(Grain g) throws CelestaException {
		// выставление в статус updating
		grain.get(g.getName());
		grain.setState(GrainsCursor.UPGRADING);
		grain.update();
		ConnectionPool.commit(grain.callContext().getConn());

		// теперь собственно обновление гранулы
		try {
			// Схему создаём, если ещё не создана.
			dba.createSchemaIfNotExists(g.getName());

			// Выполняем удаление ненужных индексов, чтобы облегчить задачу
			// обновления столбцов на таблицах.
			dropOrphanedGrainIndices(g);

			// Обновляем все таблицы.
			table.setRange("grainid", g.getName());
			while (table.next()) {
				table.setOrphaned(!g.getTables().containsKey(
						table.getTablename()));
				table.update();
			}
			for (Table t : g.getTables().values()) {
				updateTable(t);
				table.setGrainid(g.getName());
				table.setTablename(t.getName());
				table.setOrphaned(false);
				table.tryInsert();
			}

			// Обновляем все индексы.
			updateGrainIndices(g);

			// Обновляем внешние ключи
			// TODO обновление внешних ключей

			// По завершении -- обновление номера версии, контрольной суммы
			// и выставление в статус ready
			grain.setState(GrainsCursor.READY);
			grain.setChecksum(String.format("%08X", g.getChecksum()));
			grain.setLength(g.getLength());
			grain.setLastmodified(new Date());
			grain.setMessage("");
			grain.setVersion(g.getVersion().toString());
			grain.update();
			ConnectionPool.commit(grain.callContext().getConn());
			return true;
		} catch (CelestaException e) {
			// Если что-то пошло не так
			grain.setState(GrainsCursor.ERROR);
			grain.setMessage(String.format("%s/%d/%08X: %s", g.getVersion()
					.toString(), g.getLength(), g.getChecksum(), e.getMessage()));
			grain.update();
			ConnectionPool.commit(grain.callContext().getConn());
			return false;
		}
	}

	private static void dropOrphanedGrainIndices(Grain g)
			throws CelestaException {
		/*
		 * В целом метод повторяет код updateGrainIndices, но только в части
		 * удаления индексов. Зачистить все индексы, подвергшиеся удалению или
		 * изменению необходимо перед тем, как будет выполняться обновление
		 * структуры таблиц, чтобы увеличить вероятность успешного результата:
		 * висящие на полях индексы могут помешать процессу.
		 */
		Map<DBIndexInfo, TreeMap<Short, String>> dbIndices = dba.getIndices(
				grain.callContext().getConn(), g);
		Map<String, Index> myIndices = g.getIndices();
		// Удаление не существующих в метаданных индексов
		for (DBIndexInfo dBIndexInfo : dbIndices.keySet())
			if (!myIndices.containsKey(dBIndexInfo.getIndexName()))
				dba.dropIndex(g, dBIndexInfo);

		// Удаление индексов, которые будут в дальнейшем изменены, перед
		// обновлением таблиц.
		for (Entry<String, Index> e : myIndices.entrySet()) {
			DBIndexInfo dBIndexInfo = new DBIndexInfo(e.getValue().getTable()
					.getName(), e.getKey());
			if (dbIndices.containsKey(dBIndexInfo)) {
				Collection<String> dbIndexCols = dbIndices.get(dBIndexInfo)
						.values();
				Collection<String> metaIndexCols = e.getValue().getColumns()
						.keySet();
				Iterator<String> i1 = dbIndexCols.iterator();
				Iterator<String> i2 = metaIndexCols.iterator();
				boolean equals = dbIndexCols.size() == metaIndexCols.size();
				while (i1.hasNext() && equals) {
					equals = i1.next().equals(i2.next()) && equals;
				}
				if (!equals)
					dba.dropIndex(g, dBIndexInfo);
			}
		}

	}

	private static void updateGrainIndices(Grain g) throws CelestaException {
		Map<DBIndexInfo, TreeMap<Short, String>> dbIndices = dba.getIndices(
				grain.callContext().getConn(), g);
		Map<String, Index> myIndices = g.getIndices();
		// Начинаем с удаления ненужных индексов (ещё раз)

		for (DBIndexInfo dBIndexInfo : dbIndices.keySet())
			if (!myIndices.containsKey(dBIndexInfo.getIndexName()))
				/*
				 * NB Ошибка "не могу удалить не существующий индекс"? У меня
				 * нет уверенности в том, что JDBC getMetaData работает
				 * корректно в случае, когда индекс, к примеру, был удалён в
				 * процедуре dropOrphanedGrainIndices. Если моё предположение
				 * верно, то правильное решение -- переписать метод
				 * dba.getIndices, чтобы он не зависел от JDBC metaData и не
				 * возвращал бы уже удалённые индексы.
				 */
				dba.dropIndex(g, dBIndexInfo);

		// Обновление и создание нужных индексов
		for (Entry<String, Index> e : myIndices.entrySet()) {
			DBIndexInfo dBIndexInfo = new DBIndexInfo(e.getValue().getTable()
					.getName(), e.getKey());
			if (dbIndices.containsKey(dBIndexInfo)) {
				// БД содержит индекс с таким именем, надо проверить
				// поля и пересоздать индекс в случае необходимости.
				Collection<String> dbIndexCols = dbIndices.get(dBIndexInfo)
						.values();
				Collection<String> metaIndexCols = e.getValue().getColumns()
						.keySet();
				Iterator<String> i1 = dbIndexCols.iterator();
				Iterator<String> i2 = metaIndexCols.iterator();
				boolean equals = dbIndexCols.size() == metaIndexCols.size();
				while (i1.hasNext() && equals) {
					equals = i1.next().equals(i2.next()) && equals;
				}
				if (!equals) {
					dba.dropIndex(g, dBIndexInfo);
					dba.createIndex(e.getValue());
				}
			} else {
				// Создаём не существовавший ранее индекс.
				dba.createIndex(e.getValue());
			}
		}
	}

	private static void updateTable(Table t) throws CelestaException {
		if (dba.tableExists(t.getGrain().getName(), t.getName())) {
			Set<String> dbColumns = dba.getColumns(grain.callContext()
					.getConn(), t);
			for (Entry<String, Column> e : t.getColumns().entrySet()) {
				if (dbColumns.contains(e.getKey())) {
					// Таблица содержит колонку с таким именем, надо проверить
					// все её атрибуты и при необходимости -- попытаться
					// обновить.
					DBColumnInfo ci = dba.getColumnInfo(grain.callContext()
							.getConn(), e.getValue());
					if (!ci.reflects(e.getValue()))
						dba.updateColumn(grain.callContext().getConn(),
								e.getValue(), ci);
				} else {
					// Таблица не содержит колонку с таким именем, добавляем
					dba.createColumn(grain.callContext().getConn(),
							e.getValue());
				}
			}
		} else {
			// Таблицы не существует вовсе, создаём с нуля.
			dba.createTable(t);
		}
	}

}
