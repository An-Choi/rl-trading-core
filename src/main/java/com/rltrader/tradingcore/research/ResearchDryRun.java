package com.rltrader.tradingcore.research;

import com.rltrader.tradingcore.config.JacksonConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Plain CLI: no Spring context, broker client, account credentials or order API. */
public final class ResearchDryRun {
    private static final ObjectMapper MAPPER = JacksonConfig.strictMapper();
    private static final List<Double> WEIGHTS = List.of(0., .2, .4, .6, .8, 1.);
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    ResearchDryRun(String url) {
        base = URI.create(url);
        if (!"http".equals(base.getScheme()) || !List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(base.getHost())
                || base.getUserInfo() != null || base.getQuery() != null)
            throw new IllegalArgumentException("research client requires a loopback HTTP URL");
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException("object required");
        return (Map<String,Object>) value;
    }

    static double number(Map<String,Object> value, String key) {
        if (!(value.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException("finite number required: " + key);
        return n.doubleValue();
    }

    static long integer(Map<String,Object> value, String key) {
        double result = number(value, key);
        if (result != Math.rint(result) || result < Long.MIN_VALUE || result > Long.MAX_VALUE)
            throw new IllegalArgumentException("integer required: " + key);
        return (long) result;
    }

    static boolean close(double a, double b) {
        return Math.abs(a-b) <= 1e-5 + 1e-12*Math.max(Math.abs(a),Math.abs(b));
    }

    static ResearchLedger ledger(Map<String,Object> value) {
        return new ResearchLedger((String)value.get("session_id"),(String)value.get("model_sha256"),
                number(value,"cash"),integer(value,"shares"),number(value,"cost_basis"),integer(value,"decisions"));
    }

    static Map<String,Object> payload(ResearchLedger ledger) {
        return Map.of("session_id",ledger.sessionId(),"model_sha256",ledger.modelSha256(),
                "cash",ledger.cash(),"shares",ledger.shares(),"cost_basis",ledger.costBasis(),"decisions",ledger.decisions());
    }

    static boolean sameState(ResearchLedger actual, ResearchLedger expected) {
        return actual.sessionId().equals(expected.sessionId())
                && actual.modelSha256().equals(expected.modelSha256())
                && actual.decisions()==expected.decisions() && actual.shares()==expected.shares()
                && close(actual.cash(),expected.cash()) && close(actual.costBasis(),expected.costBasis());
    }

    Map<String,Object> request(String path, Map<String,Object> body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30));
        if (body == null) builder.GET();
        else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        var response = client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IllegalStateException("research HTTP status " + response.statusCode());
        return object(MAPPER.readValue(response.body(),Map.class));
    }

    static int handshake(Map<String,Object> meta) {
        var contract = object(meta.get("contract"));
        if (!"research-dry-run-v1".equals(meta.get("api_contract"))
                || !Boolean.TRUE.equals(meta.get("research_only"))
                || !Boolean.FALSE.equals(meta.get("broker_orders_enabled"))
                || !"controlled-exposure-v1".equals(contract.get("version"))
                || !"target-weight-6".equals(contract.get("action_schema"))
                || integer(contract,"n_actions")!=6)
            throw new IllegalArgumentException("unsupported research contract");
        var weights = (List<?>)meta.get("target_weights");
        if (weights == null || weights.size()!=6) throw new IllegalArgumentException("invalid target weights");
        for (int i=0;i<6;i++) {
            if (!(weights.get(i) instanceof Number n) || !close(n.doubleValue(),WEIGHTS.get(i)))
                throw new IllegalArgumentException("unknown target grid");
        }
        var shape = (List<?>)contract.get("observation_shape");
        if (shape == null || shape.size()!=1 || !(shape.get(0) instanceof Number n)
                || n.intValue()<1 || n.doubleValue()!=n.intValue())
            throw new IllegalArgumentException("invalid observation shape");
        return n.intValue();
    }

    static ResearchLedger apply(ResearchLedger ledger, Map<String,Object> response, int observationSize) {
        if (integer(response,"decision_index")!=ledger.decisions()+1) throw new IllegalArgumentException("stale decision");
        int action = Math.toIntExact(integer(response,"action"));
        var mask = (List<?>)response.get("action_mask");
        var observation = (List<?>)response.get("observation");
        var probabilities = (List<?>)response.get("probabilities");
        if (action<0 || action>=6 || mask==null || mask.size()!=6 || !Boolean.TRUE.equals(mask.get(action))
                || mask.stream().anyMatch(item -> !(item instanceof Boolean))
                || observation==null || observation.size()!=observationSize
                || observation.stream().anyMatch(item -> !(item instanceof Number n) || !Double.isFinite(n.doubleValue()))
                || probabilities==null || probabilities.size()!=6)
            throw new IllegalArgumentException("malformed research prediction");
        double sum=0, best=-1; int argmax=0;
        for (int i=0;i<6;i++) {
            if (!(probabilities.get(i) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0)
                throw new IllegalArgumentException("invalid policy probabilities");
            double p=n.doubleValue(); sum+=p;
            if (p>best) { best=p; argmax=i; }
        }
        if (Math.abs(sum-1)>1e-5 || argmax!=action || !close(number(response,"target_weight"),WEIGHTS.get(action)))
            throw new IllegalArgumentException("policy/action mismatch");
        long planned=ledger.plan(WEIGHTS.get(action),number(response,"mark_price"),number(response,"rebalance_tolerance"));
        if (planned!=integer(response,"requested_quantity")) throw new IllegalArgumentException("order planner mismatch");
        var events = (List<?>)response.get("events");
        if (events==null) throw new IllegalArgumentException("missing executions");
        int targets=0;
        for (Object item: events) {
            var event=object(item);
            long requested=integer(event,"requested_quantity"), quantity=integer(event,"quantity");
            if ("target".equals(event.get("reason"))) {
                targets++;
                if (targets>1 || requested!=planned || (requested>=0 && (quantity<0 || quantity>requested))
                        || (requested<0 && (quantity>0 || quantity<requested)))
                    throw new IllegalArgumentException("fill exceeds causal plan");
            } else if (!"settlement".equals(event.get("reason")) || quantity!=-ledger.shares() || requested!=quantity) {
                throw new IllegalArgumentException("invalid settlement");
            }
            if (Boolean.FALSE.equals(event.get("fill_available")) && (quantity!=0 || number(event,"cost")!=0))
                throw new IllegalArgumentException("unavailable fill executed");
            ledger=ledger.fill(quantity,number(event,"price"),number(event,"cost"));
        }
        if (planned!=0 && targets==0) throw new IllegalArgumentException("missing target order");
        ledger=ledger.nextDecision();
        ResearchLedger expected=ledger(object(response.get("state")));
        if (!sameState(ledger,expected))
            throw new IllegalArgumentException("independent Java/Python ledger mismatch");
        return ledger;
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options=new LinkedHashMap<>();
        for (int i=0;i<args.length;i+=2) {
            if (i+1>=args.length) throw new IllegalArgumentException("arguments require values");
            options.put(args[i],args[i+1]);
        }
        Path output=Path.of(options.getOrDefault("--output","research-dry-run"));
        if (Files.exists(output)) throw new IllegalArgumentException("output exists");
        Files.createDirectories(output);
        var replay=new ResearchDryRun(options.getOrDefault("--url","http://127.0.0.1:8766"));
        var meta=replay.request("/metadata",null); int dimension=handshake(meta);
        ResearchLedger state;
        if (options.containsKey("--resume")) {
            state=ledger(object(MAPPER.readValue(Files.readString(Path.of(options.get("--resume"))),Map.class)));
            ResearchLedger remote=ledger(replay.request("/state",null));
            if (!sameState(state,remote)) throw new IllegalArgumentException("resume/server mismatch");
        } else state=ledger(replay.request("/reset",Map.of()));
        if (!state.modelSha256().equals(meta.get("model_sha256"))) throw new IllegalArgumentException("artifact mismatch");
        int restartAt=Integer.parseInt(options.getOrDefault("--restart-at","25"));
        int maximum=Integer.parseInt(options.getOrDefault("--max-decisions","0"));
        boolean done=false, restored=false; int fills=0;
        while (!done && (maximum==0 || state.decisions()<maximum)) {
            var response=replay.request("/step",payload(state));
            state=apply(state,response,dimension);
            fills+=((List<?>)response.get("events")).size();
            done=Boolean.TRUE.equals(object(response.get("state")).get("done"));
            Files.writeString(output.resolve("trace.jsonl"),MAPPER.writeValueAsString(response)+"\n",
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            Files.writeString(output.resolve("portfolio.json"),MAPPER.writeValueAsString(payload(state))+"\n");
            if (state.decisions()==restartAt) {
                state=ledger(object(MAPPER.readValue(Files.readString(output.resolve("portfolio.json")),Map.class)));
                restored=true;
            }
        }
        var summary=Map.of("completed",done,"decisions",state.decisions(),"events",fills,
                "cash",state.cash(),"shares",state.shares(),"cost_basis",state.costBasis(),
                "restart_verified",restored,"broker_orders_submitted",0,"research_only",true);
        Files.writeString(output.resolve("completion.json"),MAPPER.writeValueAsString(summary)+"\n");
        System.out.println(MAPPER.writeValueAsString(summary));
    }
}
