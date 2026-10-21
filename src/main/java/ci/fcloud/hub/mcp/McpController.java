package ci.fcloud.hub.mcp;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ci.fcloud.hub.config.FcloudProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Streamable HTTP 端点（单端点，2025-03-26 起取代旧 HTTP+SSE）。
 *
 * <pre>
 * POST   /mcp   JSON-RPC；默认返回 application/json，Accept 仅含 SSE 时返回单事件流
 * GET    /mcp   405（无服务端主动推送）
 * DELETE /mcp   结束会话
 * </pre>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

    private final McpServer server;
    private final McpSessionStore sessions;
    private final ObjectMapper mapper;
    private final FcloudProperties props;

    @PostMapping("/mcp")
    public void post(@RequestBody(required = false) String raw,
                     HttpServletRequest request,
                     HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");

        if (!props.getMcp().isEnabled()) {
            response.setStatus(404);
            writeJson(response, mapper.createObjectNode().put("error", "mcp disabled"));
            return;
        }

        JsonNode body = parse(raw);
        if (body == null) {
            writeRpc(request, response, error(-32700, "parse error"));
            return;
        }
        if (body.isArray()) {
            writeRpc(request, response, error(-32600, "batch requests are not supported"));
            return;
        }

        McpPrincipal principal = (McpPrincipal) request.getAttribute(McpAuthFilter.ATTR_PRINCIPAL);
        if (principal == null) {
            // 理论上 McpAuthFilter 已拦截，这里兜底
            response.setStatus(401);
            writeJson(response, mapper.createObjectNode().put("error", "unauthorized"));
            return;
        }

        String method = body.path("method").asText("");
        String headerSession = request.getHeader("Mcp-Session-Id");
        McpSessionStore.Session session;

        if ("initialize".equals(method)) {
            session = sessions.create(body.path("params").path("protocolVersion")
                    .asText(McpServer.LATEST_PROTOCOL));
            response.setHeader("Mcp-Session-Id", session.id());
        } else if (headerSession == null || headerSession.isBlank()) {
            // 宽容：未带 session 也能用（部分轻量客户端不回传 header），
            // 新建一个并把 id 通过响应头告知。
            session = sessions.create(McpServer.LATEST_PROTOCOL);
            response.setHeader("Mcp-Session-Id", session.id());
        } else {
            session = sessions.get(headerSession).orElse(null);
            if (session == null) {
                // 规范：session 不存在返回 404，客户端应重新 initialize
                response.setStatus(404);
                writeJson(response, mapper.createObjectNode().put("error", "session not found"));
                return;
            }
        }

        JsonNode result = server.handle(body, principal, session);
        if (result == null) {
            // 通知：202 + 无 body
            response.setStatus(202);
            return;
        }
        writeRpc(request, response, result);
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(405).header("Allow", "POST, DELETE").build();
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "Mcp-Session-Id", required = false) String sessionId) {
        sessions.remove(sessionId);
        return ResponseEntity.noContent().build();
    }

    // ---------------- 辅助 ----------------

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode error(int code, String message) {
        var resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.putNull("id");
        var err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return resp;
    }

    /**
     * 客户端若只接受 SSE（Accept 不含 application/json），返回单事件流；
     * 否则返回普通 JSON —— 绝大多数客户端两种都收，JSON 更省事。
     */
    private void writeRpc(HttpServletRequest request, HttpServletResponse response,
                          JsonNode payload) throws IOException {
        String accept = request.getHeader("Accept");
        boolean sseOnly = accept != null && accept.contains("text/event-stream")
                && !accept.contains("application/json");
        String json = mapper.writeValueAsString(payload);

        if (sseOnly) {
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.getWriter().write("event: message\ndata: " + json + "\n\n");
        } else {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(json);
        }
        response.getWriter().flush();
    }

    private void writeJson(HttpServletResponse response, JsonNode node) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getWriter(), node);
    }
}
