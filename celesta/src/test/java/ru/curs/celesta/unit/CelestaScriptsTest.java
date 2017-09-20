package ru.curs.celesta.unit;

import org.junit.jupiter.api.*;
import org.python.core.*;
import ru.curs.celesta.*;

import java.sql.Connection;
import java.util.*;
import java.util.stream.Stream;

/**
 * Created by ioann on 13.09.2017.
 */
public class CelestaScriptsTest {

  static Celesta celesta;
  static Connection globalConn;
  static SessionContext sessionContext;
  static CallContext globalCallContext;

  Connection conn;
  CallContext context;

  public static Map<PyType, List<String>> testTypesAndTheirMethods = new LinkedHashMap<>();

  public CelestaScriptsTest() {
    System.out.println("NEW CELESTAUNIT!!!");
  }

  @BeforeAll
  public static void init() throws CelestaException {
    //Properties properties = new Properties();
    //properties.put("score.path", "E:/WorkSpace/Curs/Celesta/score");
    //properties.put("h2.in-memory", "true");

    //Celesta.initialize(properties);
    //Celesta.reInitialize();
    celesta = Celesta.getInstance();
    sessionContext = new SessionContext("super", "debug");
    globalConn = ConnectionPool.get();
    globalCallContext = new CallContext(globalConn, sessionContext);
  }

  @BeforeEach
  public void setUp() throws CelestaException {
    conn = ConnectionPool.get();
    context = new CallContext(conn, sessionContext);
  }

  @AfterEach
  public void tearDown() {
    context.closeCursors();
    ConnectionPool.putBack(conn);
  }

  @AfterAll
  public static void destroy() {
    globalCallContext.closeCursors();
    ConnectionPool.putBack(globalConn);
  }

  @TestFactory
  public Stream<DynamicTest> testScripts() {
    return testTypesAndTheirMethods.entrySet().stream()
        .flatMap(e -> {
          PyObject pyInstance = e.getKey().__call__();
          pyInstance.__setattr__("context", Py.java2py(context));
          return e.getValue().stream()
              .map(method -> DynamicTest.dynamicTest(method, () -> {
                System.out.println("Running " + method);
                pyInstance.invoke(method);
              }));
        });
  }

}
