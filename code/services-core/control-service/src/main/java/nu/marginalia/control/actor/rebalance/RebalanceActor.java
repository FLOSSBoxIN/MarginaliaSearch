package nu.marginalia.control.actor.rebalance;

import com.google.inject.Inject;
import com.zaxxer.hikari.HikariDataSource;
import nu.marginalia.actor.ActorStateFactory;
import nu.marginalia.actor.prototype.AbstractActorPrototype;
import nu.marginalia.actor.state.ActorResumeBehavior;
import nu.marginalia.actor.state.ActorState;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.mq.MqMessageState;
import nu.marginalia.mq.outbox.MqOutbox;
import nu.marginalia.mq.persistence.MqPersistence;
import nu.marginalia.nodecfg.NodeConfigurationService;
import nu.marginalia.nodecfg.model.NodeConfiguration;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.*;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RebalanceActor  extends AbstractActorPrototype {
    // States

    public static final String INIT = "INIT";
    public static final String END = "END";

    private static final Logger logger = LoggerFactory.getLogger(RebalanceActor.class);

    private final NodeConfigurationService nodeConfigurationService;
    private final MqPersistence mqPersistence;
    private final HikariDataSource dataSource;
    private final Gson gson = GsonFactory.get();
    @Override
    public String describe() {
        return "Rebalances crawl data among the nodes";
    }

    @Inject
    public RebalanceActor(ActorStateFactory stateFactory,
                          NodeConfigurationService nodeConfigurationService,
                          MqPersistence mqPersistence, HikariDataSource dataSource)
    {
        super(stateFactory);
        this.nodeConfigurationService = nodeConfigurationService;
        this.mqPersistence = mqPersistence;
        this.dataSource = dataSource;
    }

    @ActorState(name= INIT, next = END, resume = ActorResumeBehavior.ERROR,
                description = "Rebalance!")
    public void doIt() throws Exception {
        var populations = getNodePopulations();

        if (populations.size() <= 1) {
            transition(END);
        }

        int average = (int) populations.stream().mapToInt(pop -> pop.count).average().orElse(0);
        int tolerance = average / 10;

        PriorityQueue<Sur> surplusList = new PriorityQueue<>();
        PriorityQueue<Def> deficitList = new PriorityQueue<>();

        populations.forEach(pop -> {
            int delta = pop.count - average;
            if (delta - tolerance > 0) {
                surplusList.add(new Sur(pop.node, delta));
            }
            else if (delta + tolerance < 0) {
                deficitList.add(new Def(pop.node, -delta));
            }
        });

        List<Give> actions = new ArrayList<>();

        while (!surplusList.isEmpty() && !deficitList.isEmpty()) {
            var sur = surplusList.poll();
            var def = deficitList.poll();

            assert (sur.n != def.n);

            int amount = Math.min(sur.c, def.c);
            actions.add(new Give(sur.n, def.n, amount));

            if (sur.c - amount > tolerance) {
                surplusList.add(new Sur(sur.n, sur.c - amount));
            }
            if (def.c - amount > tolerance) {
                deficitList.add(new Def(def.n, def.c - amount));
            }
        }

        for (var action : actions) {
            var outbox = new MqOutbox(mqPersistence, "executor-service", action.dest, getClass().getSimpleName(), 0, UUID.randomUUID());
            var msg = outbox.send("TRANSFER-DOMAINS",
                    gson.toJson(Map.of("sourceNode", action.donor, "count", action.c)));
            if (msg.state() != MqMessageState.OK) {
                logger.error("ERROR! {}", msg);
            }
            outbox.stop();
        }

    }

    private List<Pop> getNodePopulations() throws SQLException {
        Map<Integer, Integer> ret = new HashMap<>();

        try (var conn = dataSource.getConnection();
             var query = conn.prepareStatement("""
                     SELECT NODE_AFFINITY, COUNT(*)
                     FROM EC_DOMAIN
                     WHERE NODE_AFFINITY > 0
                     GROUP BY NODE_AFFINITY
                     """)) {
            var rs = query.executeQuery();
            while (rs.next()) {
                ret.put(rs.getInt(1), rs.getInt(2));
            }
        }

        for (var node : nodeConfigurationService.getAll()) {
            if (isNodeExcluded(node)) {
                ret.remove(node.node());
            } else {
                ret.putIfAbsent(node.node(), 0);
            }
        }

        return ret.entrySet().stream().map(e -> new Pop(e.getKey(), e.getValue())).toList();
    }

    private boolean isNodeExcluded(NodeConfiguration node) {
        return node.disabled();
    }

    //* 1. calculate sizes for each node using db
    //
    //2. rebalance
    //
    //-- find average
    //-- calculate surplus and deficit, with a NN% tolerance
    //-- create instructions for redistribution
    //
    //3. instruct each executor to transfer data:
    //
    //-- transfer domain data
    //-- append to receiver crawler log
    //-- instruct donor to delete file
    //
    //4. regenerate crawler logs based on present files on all donor nodes */

    public record Sur(int n, int c) implements Comparable<Sur> {
        @Override
        public int compareTo(@NotNull RebalanceActor.Sur o) {
            int d = Integer.compare(o.c, c);
            if (d != 0)
                return d;

            return Integer.compare(n, o.n);
        }
    }
    public record Def(int n, int c) implements Comparable<Def> {

        @Override
        public int compareTo(@NotNull RebalanceActor.Def o) {
            int d = Integer.compare(o.c, c);
            if (d != 0)
                return d;

            return Integer.compare(n, o.n);
        }
    }

    public record Populations(List<Pop> pops) {
    }
    public record Pop(int node, int count) {

    }
    public record Give(int donor, int dest, int c) {

    }
}
