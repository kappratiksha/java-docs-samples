/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.appengine.firetactoe;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.appengine.tools.development.testing.LocalURLFetchServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalUserServiceTestConfig;
import com.google.cloud.datastore.QueryResults;
import com.google.cloud.testing.junit4.MultipleAttemptsRule;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyService;
import com.googlecode.objectify.util.Closeable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link TicTacToeServlet}. */
@RunWith(JUnit4.class)
public class TicTacToeServletTest {
  @Rule public final MultipleAttemptsRule multipleAttemptsRule = new MultipleAttemptsRule(5);

  private static final String USER_EMAIL = "whisky@tangofoxtr.ot";
  private static final String USER_ID = "whiskytangofoxtrot";
  private static final String FIREBASE_DB_URL = "http://firebase.com/dburl";

  private final LocalServiceTestHelper helper =
      new LocalServiceTestHelper(
              // Set no eventual consistency, that way queries return all results.
              // http://g.co/cloud/appengine/docs/java/tools/localunittesting#Java_Writing_High_Replication_Datastore_tests
              new LocalDatastoreServiceTestConfig()
                  .setDefaultHighRepJobPolicyUnappliedJobPercentage(0),
              new LocalUserServiceTestConfig(),
              new LocalURLFetchServiceTestConfig())
              .setEnvInstance(
                  String.valueOf(ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE)))
              .setEnvEmail(USER_EMAIL)
              .setEnvAuthDomain("gmail.com")
              .setEnvAttributes(new HashMap<>(ImmutableMap
                  .of("com.google.appengine.api.users.UserService.user_id_key", USER_ID)));

  @Mock private HttpServletRequest mockRequest;
  @Mock private HttpServletResponse mockResponse;
  protected Closeable dbSession;
  @Mock RequestDispatcher requestDispatcher;

  private TicTacToeServlet servletUnderTest;

  @BeforeClass
  public static void setUpBeforeClass() {
    // Reset the Factory so that all translators work properly.
    ObjectifyService.init();
    ObjectifyService.register(Game.class);
    // Mock out the firebase config
    FirebaseChannel.firebaseConfigStream =
        new ByteArrayInputStream(String.format("databaseURL: \"%s\"", FIREBASE_DB_URL).getBytes());
  }

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    helper.setUp();
    dbSession = ObjectifyService.begin();

    // Set up a fake HTTP response.
    when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("https://timbre/"));
    when(mockRequest.getRequestDispatcher("/WEB-INF/view/index.jsp")).thenReturn(requestDispatcher);

    servletUnderTest = new TicTacToeServlet();

    helper.setEnvIsLoggedIn(true);
  }

  @After
  public void tearDown() {
    dbSession.close();
    helper.tearDown();
  }

  /**
   * Compares game results before and after to find new game. Objectify v6 does
   * not guarantee new record is first.
   *
   * @param before query containing games before inserting
   * @param after query containing games after inserting
   * @return the game that was not in the original query
   */
  private Game getNewGame(QueryResults<Game> before, QueryResults<Game> after) {
    Set<String> gameKeys = new HashSet<>();
    while (before.hasNext()) {
      String gameKey = before.next().id;
      gameKeys.add(gameKey);
    }
    while (after.hasNext()) {
      Game game = after.next();
      if (!gameKeys.contains(game.id)) {
        return game;
      }
    }
    return null;
  }

  @Test
  public void doGetNoGameKey() throws Exception {
    // Mock out the firebase response. See
    // http://g.co/dv/api-client-library/java/google-http-java-client/unit-testing
    MockHttpTransport mockHttpTransport =
        spy(
            new MockHttpTransport() {
              @Override
              public LowLevelHttpRequest buildRequest(String method, String url)
                  throws IOException {
                return new MockLowLevelHttpRequest() {
                  @Override
                  public LowLevelHttpResponse execute() throws IOException {
                    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                    response.setStatusCode(200);
                    return response;
                  }
                };
              }
            });
    FirebaseChannel.getInstance().httpTransport = mockHttpTransport;

    // Make sure the game object was created for a new game
    Objectify ofy = ObjectifyService.ofy();
    QueryResults<Game> before = ofy.load().type(Game.class).iterator();
    servletUnderTest.doGet(mockRequest, mockResponse);
    QueryResults<Game> after = ofy.load().type(Game.class).iterator();
    Game game = getNewGame(before, after);

    assertThat(game.userX).isEqualTo(USER_ID);

    verify(mockHttpTransport, times(1)).buildRequest(eq("PATCH"),
        ArgumentMatchers.matches(FIREBASE_DB_URL + "/channels/[\\w-]+.json$"));
    verify(requestDispatcher).forward(mockRequest, mockResponse);
    verify(mockRequest).setAttribute(eq("token"), anyString());
    verify(mockRequest).setAttribute("game_key", game.id);
    verify(mockRequest).setAttribute("me", USER_ID);
    verify(mockRequest).setAttribute("channel_id", USER_ID + game.id);
    verify(mockRequest).setAttribute(eq("initial_message"), anyString());
    verify(mockRequest).setAttribute(eq("game_link"), anyString());
  }

  @Test
  public void doGetExistingGame() throws Exception {
    // Mock out the firebase response. See
    // http://g.co/dv/api-client-library/java/google-http-java-client/unit-testing
    MockHttpTransport mockHttpTransport =
        spy(
            new MockHttpTransport() {
              @Override
              public LowLevelHttpRequest buildRequest(String method, String url)
                  throws IOException {
                return new MockLowLevelHttpRequest() {
                  @Override
                  public LowLevelHttpResponse execute() throws IOException {
                    MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                    response.setStatusCode(200);
                    return response;
                  }
                };
              }
            });
    FirebaseChannel.getInstance().httpTransport = mockHttpTransport;

    // Insert a game
    Objectify ofy = ObjectifyService.ofy();
    Game game = new Game("some-other-user-id", null, "         ", true);
    ofy.save().entity(game).now();
    String gameKey = game.getId();

    when(mockRequest.getParameter("gameKey")).thenReturn(gameKey);

    servletUnderTest.doGet(mockRequest, mockResponse);

    // Make sure the game object was updated with the other player
    game = ofy.load().type(Game.class).id(gameKey).safe();
    assertThat(game.userX).isEqualTo("some-other-user-id");
    assertThat(game.userO).isEqualTo(USER_ID);

    verify(mockHttpTransport, times(2)).buildRequest(eq("PATCH"),
        ArgumentMatchers.matches(FIREBASE_DB_URL + "/channels/[\\w-]+.json$"));
    verify(requestDispatcher).forward(mockRequest, mockResponse);
    verify(mockRequest).setAttribute(eq("token"), anyString());
    verify(mockRequest).setAttribute("game_key", game.id);
    verify(mockRequest).setAttribute("me", USER_ID);
    verify(mockRequest).setAttribute("channel_id", USER_ID + gameKey);
    verify(mockRequest).setAttribute(eq("initial_message"), anyString());
    verify(mockRequest).setAttribute(eq("game_link"), anyString());
  }

  @Test
  public void doGetNonExistentGame() throws Exception {
    when(mockRequest.getParameter("gameKey")).thenReturn("does-not-exist");

    servletUnderTest.doGet(mockRequest, mockResponse);

    verify(mockResponse).sendError(404);
  }
}
