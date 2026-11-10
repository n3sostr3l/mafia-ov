package ru.polemica.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {
  private final LiveUpdates live;

  WebSocketConfig(LiveUpdates live) {
    this.live = live;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(live, "/ws/games/*")
        .addInterceptors(live.guard())
        .setAllowedOriginPatterns("*");
  }
}

@Component
class LiveUpdates extends TextWebSocketHandler {
  private final GameService service;
  private final ObjectMapper mapper;
  private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

  LiveUpdates(GameService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  HandshakeInterceptor guard() {
    return new HandshakeInterceptor() {
      public boolean beforeHandshake(
          ServerHttpRequest request,
          ServerHttpResponse response,
          WebSocketHandler handler,
          Map<String, Object> attrs) {
        try {
          String path = request.getURI().getPath();
          String id = path.substring(path.lastIndexOf('/') + 1);
          String query = request.getURI().getRawQuery();
          String token = null;
          if (query != null)
            for (String pair : query.split("&")) {
              String[] p = pair.split("=", 2);
              if (p[0].equals("token") && p.length == 2)
                token = URLDecoder.decode(p[1], StandardCharsets.UTF_8);
            }
          attrs.put("gameId", id);
          if (token != null) {
            service.publicGame(id, token);
            attrs.put("token", token);
            attrs.put("public", true);
          } else {
            service.getGame(id);
            attrs.put("public", false);
          }
          return true;
        } catch (Exception e) {
          response.setStatusCode(org.springframework.http.HttpStatus.NOT_FOUND);
          return false;
        }
      }

      public void afterHandshake(
          ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {}
    };
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) {
    clients.put(socket.getId(), socket);
    send(socket);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    clients.remove(socket.getId());
  }

  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    if (message.getPayload().equals("ping")) send(socket);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void changed(GameService.Changed event) {
    for (var s : clients.values()) if (event.id().equals(s.getAttributes().get("gameId"))) send(s);
  }

  private void send(WebSocketSession socket) {
    try {
      String id = (String) socket.getAttributes().get("gameId");
      boolean pub = Boolean.TRUE.equals(socket.getAttributes().get("public"));
      Game g;
      if (pub) g = service.publicGame(id, (String) socket.getAttributes().get("token"));
      else {
        g = service.getGame(id);
      }
      synchronized (socket) {
        if (socket.isOpen())
          socket.sendMessage(new TextMessage(mapper.writeValueAsString(service.view(g, pub))));
      }
    } catch (Exception e) {
      clients.remove(socket.getId());
      try {
        socket.close(CloseStatus.POLICY_VIOLATION);
      } catch (Exception ignored) {
      }
    }
  }
}
