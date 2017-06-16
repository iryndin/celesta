package ru.curs.celesta.score;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by ioann on 08.06.2017.
 */
public abstract class AbstractView extends GrainElement {

  boolean distinct;
  final Map<String, Expr> columns = new LinkedHashMap<>();
  Map<String, ViewColumnMeta> columnTypes = null;
  final Map<String, FieldRef> groupByColumns = new LinkedHashMap<>();
  private final Map<String, TableRef> tables = new LinkedHashMap<>();


  public AbstractView(Grain grain, String name) throws ParseException {
    super(grain, name);
  }

  abstract String viewType();
  abstract public void delete() throws ParseException;

  /**
   * Устанавливает условие where для SQL-запроса.
   *
   * @param whereCondition условие where.
   * @throws ParseException если тип выражения неверный.
   */
  abstract void setWhereCondition(Expr whereCondition) throws ParseException;

  void selectScript(final BufferedWriter bw, SQLGenerator gen) throws IOException {
    BWWrapper bww = new BWWrapper();

    writeSelectPart(bw, gen, bww);
    writeFromPart(bw, gen);
    writeWherePart(bw, gen);
    writeGroupByPart(bw, gen);
  }


  void writeSelectPart(final BufferedWriter bw, SQLGenerator gen, BWWrapper bww) throws IOException {
    bww.append("  select ", bw);
    if (distinct)
      bww.append("distinct ", bw);

    boolean cont = false;
    for (Map.Entry<String, Expr> e : columns.entrySet()) {
      if (cont)
        bww.append(", ", bw);
      String st = gen.generateSQL(e.getValue()) + " as ";
      if (gen.quoteNames()) {
        st = st + "\"" + e.getKey() + "\"";
      } else {
        st = st + e.getKey();
      }
      bww.append(st, bw);
      cont = true;
    }
    bw.newLine();
  }

  void writeFromPart(final BufferedWriter bw, SQLGenerator gen) throws IOException {
    bw.write("  from ");
    boolean cont = false;
    for (TableRef tRef : getTables().values()) {
      if (cont) {
        bw.newLine();
        bw.write(String.format("    %s ", tRef.getJoinType().toString()));
        bw.write("join ");
      }
      bw.write(gen.tableName(tRef));
      if (cont) {
        bw.write(" on ");
        bw.write(gen.generateSQL(tRef.getOnExpr()));
      }
      cont = true;
    }
  }

  void writeWherePart(final BufferedWriter bw, SQLGenerator gen) throws IOException {}

  void writeGroupByPart(final BufferedWriter bw, SQLGenerator gen) throws IOException {
    if (!groupByColumns.isEmpty()) {
      bw.newLine();
      bw.write(" group by ");

      int countOfProcessed = 0;
      for (Expr field : groupByColumns.values()) {
        bw.write(gen.generateSQL(field));

        if (++countOfProcessed != groupByColumns.size()) {
          bw.write(", ");
        }
      }

    }
  }

  /**
   * Добавляет колонку к представлению.
   *
   * @param alias Алиас колонки.
   * @param expr  Выражение колонки.
   * @throws ParseException Неуникальное имя алиаса или иная семантическая ошибка
   */
  void addColumn(String alias, Expr expr) throws ParseException {
    if (expr == null)
      throw new IllegalArgumentException();

    if (alias == null || alias.isEmpty())
      throw new ParseException(String.format("%s '%s' contains a column with undefined alias.", viewType(), getName()));
    if (columns.containsKey(alias))
      throw new ParseException(String.format(
          "%s '%s' already contains column with name or alias '%s'. Use unique aliases for %s columns.",
          viewType(), getName(), alias, viewType()));

    columns.put(alias, expr);
  }


  /**
   * Добавляет колонку к выражению "GROUP BY" представления.
   *
   * @param fr Выражение колонки.
   * @throws ParseException Неуникальное имя алиаса, отсутствие колонки в выборке или иная семантическая ошибка
   */
  void addGroupByColumn(FieldRef fr) throws ParseException {
    if (fr == null)
      throw new IllegalArgumentException();

    String alias = fr.getColumnName();

    if (groupByColumns.containsKey(alias))
      throw new ParseException(String.format(
          "Duplicate column '%s' in GROUP BY expression for %s '%s.%s'.",
          alias, viewType(), getGrain().getName(), getName()));

    groupByColumns.put(alias, fr);
  }

  /**
   * Добавляет ссылку на таблицу к представлению.
   *
   * @param ref Ссылка на таблицу.
   * @throws ParseException Неуникальный алиас или иная ошибка.
   */
  void addFromTableRef(TableRef ref) throws ParseException {
    if (ref == null)
      throw new IllegalArgumentException();

    String alias = ref.getAlias();
    if (alias == null || alias.isEmpty())
      throw new ParseException(String.format("%s '%s' contains a table with undefined alias.", viewType(), getName()));
    if (getTables().containsKey(alias))
      throw new ParseException(String.format(
          "%s, '%s' already contains table with name or alias '%s'. Use unique aliases for %s tables.",
          viewType(), getName(), alias, viewType()));

    getTables().put(alias, ref);

    Expr onCondition = ref.getOnExpr();
    if (onCondition != null) {
      onCondition.resolveFieldRefs(new ArrayList<>(getTables().values()));
      onCondition.validateTypes();
    }
  }


  /**
   * Финализирует разбор представления, разрешая ссылки на поля и проверяя
   * типы выражений.
   *
   * @throws ParseException ошибка проверки типов или разрешения ссылок.
   */
  abstract void finalizeParsing() throws ParseException;

  void finalizeColumnsParsing() throws ParseException {
    List<TableRef> t = new ArrayList<>(getTables().values());
    for (Expr e : columns.values()) {
      e.resolveFieldRefs(t);
      e.validateTypes();
    }
  }


  void finalizeGroupByParsing() throws ParseException {
    //Проверяем, что колонки, не использованные для агрегации, перечислены в выражении GROUP BY
    Set<String> aggregateAliases = columns.entrySet().stream()
        .filter(e -> e.getValue() instanceof Aggregate)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());

    if ((!aggregateAliases.isEmpty() && aggregateAliases.size() != columns.size())
        || !groupByColumns.isEmpty()) {

      //Бежим по колонкам, которые не агрегаты, и бросаем исключение,
      // если хотя бы одна из них не присутствует в groupByColumns
      Optional hasErrorOpt = columns.entrySet().stream()
          .filter(e -> !(e.getValue() instanceof Aggregate) && !groupByColumns.containsKey(e.getKey()))
          .findFirst();

      if (hasErrorOpt.isPresent()) {
        throw new ParseException(String.format("%s '%s.%s' contains a column(s) " +
                "which was not specified in aggregate function and GROUP BY expression.",
            viewType(), getGrain().getName(), getName()));
      }
    }
  }

  /**
   * Использовано ли слово DISTINCT в запросе представления.
   */
  boolean isDistinct() {
    return distinct;
  }

  /**
   * Устанавливает использование слова DISTINCT в запросе представления.
   *
   * @param distinct Если запрос имеет вид SELECT DISTINCT.
   */
  void setDistinct(boolean distinct) {
    this.distinct = distinct;
  }

  /**
   * Возвращает перечень столбцов представления.
   */
  public abstract Map<String, ? extends ColumnMeta> getColumns();


  Map<String, TableRef> getTables() {
    return tables;
  }

  @Override
  public int getColumnIndex(String name) {
    int i = -1;
    for (String c : columnTypes.keySet()) {
      i++;
      if (c.equals(name))
        return i;
    }
    return i;
  }


  /**
   * Wrapper for automatic line-breaks.
   */
  class BWWrapper {
    private static final int LINE_SIZE = 80;
    private static final String PADDING = "    ";
    private int l = 0;

    private void append(String s, BufferedWriter bw) throws IOException {
      bw.write(s);
      l += s.length();
      if (l >= LINE_SIZE) {
        bw.newLine();
        bw.write(PADDING);
        l = PADDING.length();
      }
    }
  }
}