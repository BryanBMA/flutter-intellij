package com.jetbrains.lang.dart.ide.runner.test;


import com.google.gson.*;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.ServiceMessageBuilder;
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.PathUtil;
import com.jetbrains.lang.dart.ide.runner.util.DartTestLocationProvider;
import com.jetbrains.lang.dart.util.DartUrlResolver;
import gnu.trove.TIntLongHashMap;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convert events from JSON format generated by package:test to the string format
 * expected by the event processor.
 * NOTE: The test runner runs tests asynchronously. It is possible to get a 'testDone'
 * event followed some time later by an 'error' event for that same test. That should
 * convert a successful test into a failure. That case is not being handled.
 */
@SuppressWarnings({"Duplicates", "FieldMayBeFinal", "LocalCanBeFinal", "SameReturnValue"})
public class DartTestEventsConverterZ extends OutputToGeneralTestEventsConverter {
  private static final Logger LOG = Logger.getInstance(DartTestEventsConverterZ.class);

  private static final String TYPE_START = "start";
  private static final String TYPE_SUITE = "suite";
  private static final String TYPE_ERROR = "error";
  private static final String TYPE_GROUP = "group";
  private static final String TYPE_PRINT = "print";
  private static final String TYPE_DONE = "done";
  private static final String TYPE_ALL_SUITES = "allSuites";
  private static final String TYPE_TEST_START = "testStart";
  private static final String TYPE_TEST_DONE = "testDone";

  private static final String DEF_GROUP = "group";
  private static final String DEF_SUITE = "suite";
  private static final String DEF_TEST = "test";
  private static final String DEF_METADATA = "metadata";

  private static final String JSON_TYPE = "type";
  private static final String JSON_NAME = "name";
  private static final String JSON_ID = "id";
  private static final String JSON_TEST_ID = "testID";
  private static final String JSON_SUITE_ID = "suiteID";
  private static final String JSON_PARENT_ID = "parentID";
  private static final String JSON_GROUP_IDS = "groupIDs";
  private static final String JSON_RESULT = "result";
  private static final String JSON_MILLIS = "time";
  private static final String JSON_COUNT = "count";
  private static final String JSON_TEST_COUNT = "testCount";
  private static final String JSON_MESSAGE = "message";
  private static final String JSON_ERROR_MESSAGE = "error";
  private static final String JSON_STACK_TRACE = "stackTrace";
  private static final String JSON_IS_FAILURE = "isFailure";
  private static final String JSON_PATH = "path";
  private static final String JSON_PLATFORM = "platform";
  private static final String JSON_LINE = "line";
  private static final String JSON_COLUMN = "column";
  private static final String JSON_URL = "url";

  private static final String RESULT_SUCCESS = "success";
  private static final String RESULT_FAILURE = "failure";
  private static final String RESULT_ERROR = "error";

  private static final String EXPECTED = "Expected: ";
  private static final Pattern EXPECTED_ACTUAL_RESULT = Pattern.compile("\\nExpected: (.*)\\n {2}Actual: (.*)\\n *\\^\\n Differ.*\\n");
  private static final String FILE_URL_PREFIX = "dart_location://";
  private static final String LOADING_PREFIX = "loading ";
  private static final String COMPILING_PREFIX = "compiling ";
  private static final String SET_UP_ALL_VIRTUAL_TEST_NAME = "(setUpAll)";
  private static final String TEAR_DOWN_ALL_VIRTUAL_TEST_NAME = "(tearDownAll)";

  private static final Gson GSON = new Gson();

  @NotNull private final DartUrlResolver myUrlResolver;

  private String myLocation;
  private Key myCurrentOutputType;
  private ServiceMessageVisitor myCurrentVisitor;
  private TIntLongHashMap myTestIdToTimestamp;
  private Map<Integer, Test> myTestData;
  private Map<Integer, Group> myGroupData;
  private Map<Integer, Suite> mySuiteData;
  private int mySuitCount;

  public DartTestEventsConverterZ(@NotNull final String testFrameworkName,
                                  @NotNull final TestConsoleProperties consoleProperties,
                                  @NotNull final DartUrlResolver urlResolver) {
    super(testFrameworkName, consoleProperties);
    myUrlResolver = urlResolver;
    myTestIdToTimestamp = new TIntLongHashMap();
    myTestData = new HashMap<>();
    myGroupData = new HashMap<>();
    mySuiteData = new HashMap<>();
  }

  protected boolean processServiceMessages(final String text, final Key outputType, final ServiceMessageVisitor visitor)
    throws ParseException {
    LOG.debug("<<< " + text.trim());
    myCurrentOutputType = outputType;
    myCurrentVisitor = visitor;
    // service message parser expects line like "##teamcity[ .... ]" without whitespaces in the end.
    return processEventText(text);
  }

  @SuppressWarnings("SimplifiableIfStatement")
  private boolean processEventText(final String text) throws JsonSyntaxException, ParseException {
    JsonParser jp = new JsonParser();
    JsonElement elem;
    try {
      elem = jp.parse(text);
    }
    catch (JsonSyntaxException ex) {
      if (text.contains("\"json\" is not an allowed value for option \"reporter\"")) {
        final ServiceMessageBuilder testStarted = ServiceMessageBuilder.testStarted("Failed to start");
        final ServiceMessageBuilder testFailed = ServiceMessageBuilder.testFailed("Failed to start");
        testFailed.addAttribute("message", "Please update your pubspec.yaml dependency on package:test to version 0.12.9 or later.");
        final ServiceMessageBuilder testFinished = ServiceMessageBuilder.testFinished("Failed to start");
        return finishMessage(testStarted, 1, 0) & finishMessage(testFailed, 1, 0) & finishMessage(testFinished, 1, 0);
      }

      return doProcessServiceMessages(text);
    }

    if (elem != null && elem.isJsonArray()) return process(elem.getAsJsonArray());

    if (elem == null || !elem.isJsonObject()) return false;
    return process(elem.getAsJsonObject());
  }

  /**
   * Hook to process arrays.
   */
  protected boolean process(JsonArray array) {
    return false;
  }

  private boolean doProcessServiceMessages(@NotNull final String text) throws ParseException {
    LOG.debug(">>> " + text);
    return super.processServiceMessages(text, myCurrentOutputType, myCurrentVisitor);
  }

  @SuppressWarnings("SimplifiableIfStatement")
  private boolean process(JsonObject obj) throws JsonSyntaxException, ParseException {
    String type = obj.get(JSON_TYPE).getAsString();
    if (TYPE_TEST_START.equals(type)) {
      return handleTestStart(obj);
    }
    else if (TYPE_TEST_DONE.equals(type)) {
      return handleTestDone(obj);
    }
    else if (TYPE_ERROR.equals(type)) {
      return handleError(obj);
    }
    else if (TYPE_PRINT.equals(type)) {
      return handlePrint(obj);
    }
    else if (TYPE_GROUP.equals(type)) {
      return handleGroup(obj);
    }
    else if (TYPE_SUITE.equals(type)) {
      return handleSuite(obj);
    }
    else if (TYPE_ALL_SUITES.equals(type)) {
      return handleAllSuites(obj);
    }
    else if (TYPE_START.equals(type)) {
      return handleStart(obj);
    }
    else if (TYPE_DONE.equals(type)) {
      return handleDone(obj);
    }
    else {
      return true;
    }
  }

  private boolean handleTestStart(JsonObject obj) throws ParseException {
    final JsonObject testObj = obj.getAsJsonObject(DEF_TEST);

    // Not reached if testObj == null.
    final Test test = getTest(obj);
    myTestIdToTimestamp.put(test.getId(), getTimestamp(obj));

    if (shouldTestBeHiddenIfPassed(test)) {
      // Virtual test that represents loading or compiling a test suite. See lib/src/runner/loader.dart -> Loader.loadFile() in pkg/test source code
      // At this point we do not report anything to the framework, but if error occurs, we'll report it as a normal test
      String path = "";

      if (test.getName().startsWith(LOADING_PREFIX)) {
        path = test.getName().substring(LOADING_PREFIX.length());
      }
      else if (test.getName().startsWith(COMPILING_PREFIX)) {
        path = test.getName().substring(COMPILING_PREFIX.length());
      }

      if (path.length() > 0) myLocation = FILE_URL_PREFIX + path;

      test.myTestStartReported = false;
      return true;
    }

    final ServiceMessageBuilder testStarted = ServiceMessageBuilder.testStarted(test.getBaseName());
    test.myTestStartReported = true;

    preprocessTestStart(test);

    addLocationHint(testStarted, test);
    boolean result = finishMessage(testStarted, test.getId(), test.getValidParentId());

    final Metadata metadata = Metadata.from(testObj.getAsJsonObject(DEF_METADATA));
    if (metadata.skip) {
      final ServiceMessageBuilder message = ServiceMessageBuilder.testIgnored(test.getBaseName());
      if (metadata.skipReason != null) message.addAttribute("message", metadata.skipReason);
      result &= finishMessage(message, test.getId(), test.getValidParentId());
    }

    return result;
  }

  /**
   * Hook to preprocess tests before adding location info and generating a service message.
   */
  protected void preprocessTestStart(@NotNull Test test) {
  }

  private static boolean shouldTestBeHiddenIfPassed(@NotNull final Test test) {
    // There are so called 'virtual' tests that are created for loading test suites, setUpAll(), and tearDownAll().
    // They shouldn't be visible when they do not cause problems. But if any error occurs, we'll report it later as a normal test.
    // See lib/src/runner/loader.dart -> Loader.loadFile() and lib/src/backend/declarer.dart -> Declarer._setUpAll and Declarer._tearDownAll in pkg/test source code
    final Group group = test.getParent();
    return group == null && (test.getName().startsWith(LOADING_PREFIX) || test.getName().startsWith(COMPILING_PREFIX))
           ||
           group != null && group.getDoneTestsCount() == 0 && test.getBaseName().equals(SET_UP_ALL_VIRTUAL_TEST_NAME)
           ||
           group != null && group.getDoneTestsCount() > 0 && test.getBaseName().equals(TEAR_DOWN_ALL_VIRTUAL_TEST_NAME);
  }

  private boolean handleTestDone(JsonObject obj) throws ParseException {
    final Test test = getTest(obj);

    if (!test.myTestStartReported) return true;

    String result = getResult(obj);
    if (!result.equals(RESULT_SUCCESS) && !result.equals(RESULT_FAILURE) && !result.equals(RESULT_ERROR)) {
      throw new ParseException("Unknown result: " + obj, 0);
    }

    test.testDone();

    //if (test.getMetadata().skip) return true; // skipped tests are reported as ignored in handleTestStart(). testFinished signal must follow

    ServiceMessageBuilder testFinished = ServiceMessageBuilder.testFinished(test.getBaseName());
    long duration = getTimestamp(obj) - myTestIdToTimestamp.get(test.getId());
    testFinished.addAttribute("duration", Long.toString(duration));

    return finishMessage(testFinished, test.getId(), test.getValidParentId()) && checkGroupDone(test.getParent());
  }

  @SuppressWarnings("SimplifiableIfStatement")
  private boolean checkGroupDone(@Nullable final Group group) throws ParseException {
    if (group != null && group.getTestCount() > 0 && group.getDoneTestsCount() == group.getTestCount()) {
      return processGroupDone(group) && checkGroupDone(group.getParent());
    }
    return true;
  }

  private boolean handleGroup(JsonObject obj) throws ParseException {
    final Group group = getGroup(obj.getAsJsonObject(DEF_GROUP));
    return handleGroup(group);
  }

  protected boolean handleGroup(@NotNull Group group) throws ParseException {
    // From spec: The implicit group at the root of each test suite has null name and parentID attributes.
    if (group.getParent() == null && group.getTestCount() > 0) {
      // com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter.MyServiceMessageVisitor.KEY_TESTS_COUNT
      // and  com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter.MyServiceMessageVisitor.ATTR_KEY_TEST_COUNT
      final ServiceMessageBuilder testCount =
        new ServiceMessageBuilder("testCount").addAttribute("count", String.valueOf(group.getTestCount()));
      doProcessServiceMessages(testCount.toString());
    }

    if (group.isArtificial()) return true; // Ignore artificial groups.

    ServiceMessageBuilder groupMsg = ServiceMessageBuilder.testSuiteStarted(group.getBaseName());
    // Possible attributes: "nodeType" "nodeArgs" "running"
    addLocationHint(groupMsg, group);
    return finishMessage(groupMsg, group.getId(), group.getValidParentId());
  }

  private boolean handleSuite(JsonObject obj) throws ParseException {
    Suite suite = getSuite(obj.getAsJsonObject(DEF_SUITE));
    if (!suite.hasPath()) {
      mySuiteData.remove(suite.getId());
    }
    return true;
  }

  private boolean handleError(JsonObject obj) throws ParseException {
    final Test test = getTest(obj);
    final String message = getErrorMessage(obj);
    boolean result = true;

    if (!test.myTestStartReported) {
      final ServiceMessageBuilder testStarted = ServiceMessageBuilder.testStarted(test.getBaseName());
      test.myTestStartReported = true;
      result = finishMessage(testStarted, test.getId(), test.getValidParentId());
    }

    if (test.myTestErrorReported) {
      final ServiceMessageBuilder testErrorMessage = ServiceMessageBuilder.testStdErr(test.getBaseName());
      testErrorMessage.addAttribute("out", appendLineBreakIfNeeded(message));
      result &= finishMessage(testErrorMessage, test.getId(), test.getValidParentId());
    }
    else {
      final ServiceMessageBuilder testError = ServiceMessageBuilder.testFailed(test.getBaseName());
      test.myTestErrorReported = true;

      String failureMessage = message;
      int firstExpectedIndex = message.indexOf(EXPECTED);
      if (firstExpectedIndex >= 0) {
        Matcher matcher = EXPECTED_ACTUAL_RESULT.matcher(message);
        if (matcher.find(firstExpectedIndex + EXPECTED.length())) {
          String expectedText = matcher.group(1);
          String actualText = matcher.group(2);
          testError.addAttribute("expected", expectedText);
          testError.addAttribute("actual", actualText);
          if (firstExpectedIndex == 0) {
            failureMessage = "Comparison failed";
          }
          else {
            failureMessage = message.substring(0, firstExpectedIndex);
          }
        }
      }

      if (!getBoolean(obj, JSON_IS_FAILURE)) testError.addAttribute("error", "true");
      testError.addAttribute("message", appendLineBreakIfNeeded(failureMessage));

      result &= finishMessage(testError, test.getId(), test.getValidParentId());
    }

    final String stackTrace = getStackTrace(obj);
    if (!StringUtil.isEmptyOrSpaces(stackTrace)) {
      final ServiceMessageBuilder stackTraceMessage = ServiceMessageBuilder.testStdErr(test.getBaseName());
      stackTraceMessage.addAttribute("out", appendLineBreakIfNeeded(stackTrace));
      result &= finishMessage(stackTraceMessage, test.getId(), test.getValidParentId());
    }

    return result;
  }

  @NotNull
  private static String appendLineBreakIfNeeded(@NotNull final String message) {
    return message.endsWith("\n") ? message : message + "\n";
  }

  private boolean handleAllSuites(JsonObject obj) {
    JsonElement elem = obj.get(JSON_COUNT);
    if (elem == null || !elem.isJsonPrimitive()) return true;
    mySuitCount = elem.getAsInt();
    return true;
  }

  private boolean handlePrint(JsonObject obj) throws ParseException {
    final Test test = getTest(obj);
    boolean result = true;

    if (!test.myTestStartReported) {
      if (test.getBaseName().equals(SET_UP_ALL_VIRTUAL_TEST_NAME) || test.getBaseName().equals(TEAR_DOWN_ALL_VIRTUAL_TEST_NAME)) {
        return true; // output in successfully passing setUpAll/tearDownAll is not important enough to make these nodes visible
      }

      final ServiceMessageBuilder testStarted = ServiceMessageBuilder.testStarted(test.getBaseName());
      test.myTestStartReported = true;
      result = finishMessage(testStarted, test.getId(), test.getValidParentId());
    }

    ServiceMessageBuilder message = ServiceMessageBuilder.testStdOut(test.getBaseName());
    message.addAttribute("out", appendLineBreakIfNeeded(getMessage(obj)));

    return result & finishMessage(message, test.getId(), test.getValidParentId());
  }

  private boolean handleStart(JsonObject obj) throws ParseException {
    myTestIdToTimestamp.clear();
    myTestData.clear();
    myGroupData.clear();
    mySuiteData.clear();
    mySuitCount = 0;

    return doProcessServiceMessages(new ServiceMessageBuilder("enteredTheMatrix").toString());
  }

  @SuppressWarnings("RedundantThrows")
  private boolean handleDone(JsonObject obj) throws ParseException {
    // The test runner has reached the end of the tests.
    processAllTestsDone();
    return true;
  }

  private void processAllTestsDone() {
    // All tests are done.
    for (Group group : myGroupData.values()) {
      // For package: test prior to v. 0.12.9 there were no Group.testCount field, so need to finish them all at the end.
      // AFAIK the order does not matter. A depth-first post-order traversal of the tree would work
      // if order does matter. Note: Currently, there is no tree representation, just parent links.

      if (group.getTestCount() == 0 || group.getDoneTestsCount() != group.getTestCount()) {
        try {
          processGroupDone(group);
        }
        catch (ParseException ex) {
          // ignore it
        }
      }
    }
    myTestIdToTimestamp.clear();
    myTestData.clear();
    myGroupData.clear();
    mySuiteData.clear();
    mySuitCount = 0;
  }

  private boolean processGroupDone(@NotNull final Group group) throws ParseException {
    if (group.isArtificial()) return true;

    ServiceMessageBuilder groupMsg = ServiceMessageBuilder.testSuiteFinished(group.getBaseName());
    return finishMessage(groupMsg, group.getId(), group.getValidParentId());
  }

  private boolean finishMessage(@NotNull ServiceMessageBuilder msg, int testId, int parentId) throws ParseException {
    msg.addAttribute("nodeId", String.valueOf(testId));
    msg.addAttribute("parentNodeId", String.valueOf(parentId));
    return doProcessServiceMessages(msg.toString());
  }

  private void addLocationHint(ServiceMessageBuilder messageBuilder, Item item) {
    String location = "unknown";
    String loc;

    final VirtualFile file = item.getUrl() == null ? null : myUrlResolver.findFileByDartUrl(item.getUrl());
    if (file != null) {
      loc = FILE_URL_PREFIX + file.getPath();
    }
    else if (item.hasSuite()) {
      loc = FILE_URL_PREFIX + item.getSuite().getPath();
    }
    else {
      loc = myLocation;
    }

    if (loc != null) {
      String nameList = GSON.toJson(item.nameList(), DartTestLocationProvider.STRING_LIST_TYPE);
      location = loc + "," + item.getLine() + "," + item.getColumn() + "," + nameList;
    }

    messageBuilder.addAttribute("locationHint", location);
  }

  private static long getTimestamp(JsonObject obj) throws ParseException {
    return getLong(obj, JSON_MILLIS);
  }

  private static long getLong(JsonObject obj, String name) throws ParseException {
    JsonElement val = obj == null ? null : obj.get(name);
    if (val == null || !val.isJsonPrimitive()) throw new ParseException("Value is not type long: " + val, 0);
    return val.getAsLong();
  }

  private static boolean getBoolean(JsonObject obj, String name) throws ParseException {
    JsonElement val = obj == null ? null : obj.get(name);
    if (val == null || !val.isJsonPrimitive()) throw new ParseException("Value is not type boolean: " + val, 0);
    return val.getAsBoolean();
  }

  @NotNull
  private Test getTest(JsonObject obj) throws ParseException {
    return getItem(obj, myTestData);
  }

  @NotNull
  private Group getGroup(JsonObject obj) throws ParseException {
    return getItem(obj, myGroupData);
  }

  @NotNull
  private Suite getSuite(JsonObject obj) throws ParseException {
    return getItem(obj, mySuiteData);
  }

  @NotNull
  private <T extends Item> T getItem(JsonObject obj, Map<Integer, T> items) throws ParseException {
    if (obj == null) throw new ParseException("Unexpected null json object", 0);
    T item;
    JsonElement id = obj.get(JSON_ID);
    if (id != null) {
      if (items == myTestData) {
        @SuppressWarnings("unchecked") T type = (T)Test.from(obj, myGroupData, mySuiteData);
        item = type;
      }
      else if (items == myGroupData) {
        @SuppressWarnings("unchecked") T group = (T)Group.from(obj, myGroupData, mySuiteData);
        item = group;
      }
      else {
        @SuppressWarnings("unchecked") T suite = (T)Suite.from(obj);
        item = suite;
      }
      items.put(id.getAsInt(), item);
    }
    else {
      JsonElement testId = obj.get(JSON_TEST_ID);
      if (testId != null) {
        int baseId = testId.getAsInt();
        item = items.get(baseId);
      }
      else {
        JsonElement testObj = obj.get(DEF_TEST);
        if (testObj != null) {
          return getItem(testObj.getAsJsonObject(), items);
        }
        else {
          throw new ParseException("No testId in json object", 0);
        }
      }
    }
    return item;
  }

  @NotNull
  private static String getErrorMessage(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_ERROR_MESSAGE, "<no error message>");
  }

  @NotNull
  private static String getMessage(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_MESSAGE, "<no message>");
  }

  @NotNull
  private static String getStackTrace(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_STACK_TRACE, "<no stack trace>");
  }

  @NotNull
  private static String getResult(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_RESULT, "<no result>");
  }

  @NotNull
  private static String nonNullJsonValue(JsonObject obj, @NotNull String id, @NotNull String def) {
    JsonElement val = obj == null ? null : obj.get(id);
    if (val == null || !val.isJsonPrimitive()) return def;
    return val.getAsString();
  }

  protected static class Item {
    protected static final String NO_NAME = "<no name>";
    private final int myId;

    // Visible and mutable to allow for processing.
    public String myName;
    public Group myParent;
    public String myUrl;

    private final Suite mySuite;
    private final Metadata myMetadata;
    private final int myLine;
    private final int myColumn;

    static int extractInt(JsonObject obj, String memberName) {
      JsonElement elem = obj.get(memberName);
      if (elem == null || !elem.isJsonPrimitive()) return -1;
      return elem.getAsInt();
    }

    static String extractString(JsonObject obj, String memberName, String defaultResult) {
      JsonElement elem = obj.get(memberName);
      if (elem == null || elem.isJsonNull()) return defaultResult;
      return elem.getAsString();
    }

    static Metadata extractMetadata(JsonObject obj) {
      return Metadata.from(obj.get(DEF_METADATA));
    }

    static Suite lookupSuite(JsonObject obj, Map<Integer, Suite> suites) {
      JsonElement suiteObj = obj.get(JSON_SUITE_ID);
      Suite suite = null;
      if (suiteObj != null && suiteObj.isJsonPrimitive()) {
        int parentId = suiteObj.getAsInt();
        suite = suites.get(parentId);
      }
      return suite;
    }

    Item(int id, String name, Group parent, Suite suite, Metadata metadata, int line, int column, String url) {
      myId = id;
      myName = name;
      myParent = parent;
      mySuite = suite;
      myMetadata = metadata;
      myLine = line;
      myColumn = column;
      myUrl = url;
    }

    int getId() {
      return myId;
    }

    public String getName() {
      return myName;
    }

    String getBaseName() {
      // Virtual test that represents loading or compiling a test suite. See lib/src/runner/loader.dart -> Loader.loadFile() in pkg/test source code
      if (this instanceof Test && getParent() == null) {
        if (myName.startsWith(LOADING_PREFIX)) {
          return LOADING_PREFIX + PathUtil.getFileName(myName.substring(LOADING_PREFIX.length()));
        }
        else if (myName.startsWith(COMPILING_PREFIX)) {
          return COMPILING_PREFIX + PathUtil.getFileName(myName.substring(COMPILING_PREFIX.length()));
        }
        return myName; // can't happen
      }

      // file-level group
      if (this instanceof Group && NO_NAME.equals(myName) && myParent == null && hasSuite()) {
        return PathUtil.getFileName(getSuite().getPath());
      }

      // top-level group in suite
      if (this instanceof Group && myParent != null && myParent.getParent() == null && NO_NAME.equals(myParent.getName())) {
        return myName;
      }

      if (hasValidParent()) {
        final String parentName = getParent().getName();
        if (myName.startsWith(parentName + " ")) {
          return myName.substring(parentName.length() + 1);
        }
      }

      return myName;
    }

    boolean hasSuite() {
      return mySuite != null && mySuite.hasPath();
    }

    public Suite getSuite() {
      return mySuite;
    }

    Group getParent() {
      return myParent;
    }

    Metadata getMetadata() {
      return myMetadata;
    }

    boolean isArtificial() {
      return NO_NAME.equals(myName) && myParent == null && !hasSuite();
    }

    boolean hasValidParent() {
      return !(myParent == null || myParent.isArtificial());
    }

    int getValidParentId() {
      if (hasValidParent()) {
        return getParent().getId();
      }
      else {
        return 0;
      }
    }

    List<String> nameList() {
      List<String> names = new ArrayList<>();
      addNames(names);
      return names;
    }

    void addNames(List<String> names) {
      if (this instanceof Group && NO_NAME.equals(myName) && myParent == null) {
        return; // do not add a name of a file-level group
      }

      if (myParent != null) {
        myParent.addNames(names);
      }

      names.add(StringUtil.escapeStringCharacters(getBaseName()));
    }

    public int getLine() {
      return myLine;
    }

    public int getColumn() {
      return myColumn;
    }

    public String getUrl() {
      return myUrl;
    }

    public String toString() {
      return getClass().getSimpleName() + "(" + String.valueOf(myId) + "," + String.valueOf(myName) + ")";
    }
  }

  protected static class Test extends Item {

    private static class LocationInfo {
      String url;
      int line;
      int column;
    }

    private boolean myTestStartReported = false;
    private boolean myTestErrorReported = false;

    static Test from(JsonObject obj, Map<Integer, Group> groups, Map<Integer, Suite> suites) {
      int[] groupIds = GSON.fromJson(obj.get(JSON_GROUP_IDS), (Type)int[].class);
      Group parent = null;
      if (groupIds != null && groupIds.length > 0) {
        parent = groups.get(groupIds[groupIds.length - 1]);
      }
      Suite suite = lookupSuite(obj, suites);

      final LocationInfo loc = extractLocation(obj);
      return new Test(extractInt(obj, JSON_ID), extractString(obj, JSON_NAME, NO_NAME), parent, suite, extractMetadata(obj),
                      loc.line < 0 ? -1 : loc.line - 1, loc.column < 0 ? -1 : loc.column - 1, loc.url);
    }

    private static LocationInfo extractLocation(JsonObject obj) {
      final LocationInfo info = new LocationInfo();
      // Check for root_* data first as it's more precise (when present).
      info.url = extractString(obj, "root_url", null);
      if (info.url != null) {
        info.line = extractInt(obj, "root_line");
        info.column = extractInt(obj, "root_column");
      }
      else {
        info.url = extractString(obj, JSON_URL, null);
        info.line = extractInt(obj, JSON_LINE);
        info.column = extractInt(obj, JSON_COLUMN);
      }
      return info;
    }

    Test(int id, String name, Group parent, Suite suite, Metadata metadata, int line, int column, String url) {
      super(id, name, parent, suite, metadata, line, column, url);
    }

    public void testDone() {
      if (getParent() != null) {
        getParent().incDoneTestsCount();
      }
    }
  }

  protected static class Group extends Item {
    private int myTestCount = 0;
    private int myDoneTestsCount = 0;

    static Group from(JsonObject obj, Map<Integer, Group> groups, Map<Integer, Suite> suites) {
      JsonElement parentObj = obj.get(JSON_PARENT_ID);
      Group parent = null;
      if (parentObj != null && parentObj.isJsonPrimitive()) {
        int parentId = parentObj.getAsInt();
        parent = groups.get(parentId);
      }
      Suite suite = lookupSuite(obj, suites);
      final int line = extractInt(obj, JSON_LINE);
      final int column = extractInt(obj, JSON_COLUMN);
      return new Group(extractInt(obj, JSON_ID), extractString(obj, JSON_NAME, NO_NAME), parent, suite, extractMetadata(obj),
                       extractInt(obj, JSON_TEST_COUNT), line < 0 ? -1 : line - 1, column < 0 ? -1 : column - 1,
                       extractString(obj, JSON_URL, null));
    }

    Group(int id, String name, Group parent, Suite suite, Metadata metadata, int count, int line, int column, String url) {
      super(id, name, parent, suite, metadata, line, column, url);
      myTestCount = count;
    }

    int getTestCount() {
      return myTestCount;
    }

    public int getDoneTestsCount() {
      return myDoneTestsCount;
    }

    public void incDoneTestsCount() {
      myDoneTestsCount++;

      if (getParent() != null) {
        getParent().incDoneTestsCount();
      }
    }
  }

  protected static class Suite extends Item {
    static Metadata NoMetadata = new Metadata();
    static String NONE = "<none>";

    static Suite from(JsonObject obj) {
      return new Suite(extractInt(obj, JSON_ID), extractString(obj, JSON_PATH, NONE), extractString(obj, JSON_PLATFORM, NONE));
    }

    private final String myPlatform;

    Suite(int id, String path, String platform) {
      super(id, path, null, null, NoMetadata, -1, -1, "file://" + path);
      myPlatform = platform;
    }

    String getPath() {
      return getName();
    }

    String getPlatform() {
      return myPlatform;
    }

    @SuppressWarnings("StringEquality")
    boolean hasPath() {
      return getPath() != NONE;
    }
  }

  private static class Metadata {
    @SuppressWarnings("unused") private boolean skip; // assigned by GSON via reflection
    @SuppressWarnings("unused") private String skipReason; // assigned by GSON via reflection

    static Metadata from(JsonElement elem) {
      if (elem == null) return new Metadata();
      return GSON.fromJson(elem, (Type)Metadata.class);
    }
  }
}
