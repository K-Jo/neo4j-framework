package com.graphaware.graphunit;

import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.graphdb.*;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.tooling.GlobalGraphOperations;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.neo4j.graphdb.Direction.OUTGOING;
import static org.neo4j.helpers.collection.Iterables.count;
import static org.neo4j.tooling.GlobalGraphOperations.at;

/**
 * A set of assertion methods useful for writing tests for Neo4j. Uses the {@link org.junit.Assert} class from JUnit
 * to throw {@link AssertionError}s.
 * <p/>
 * Note: This class is well-tested functionally, but it is not designed for production use, mainly because it hasn't been
 * optimised for performance. The performance will be poor on large graphs - the problem it is solving is computationally
 * quite hard!
 */
public final class GraphUnit {

    /**
     * Assert that the graph in the given database is exactly the same as the graph that would be created by the given
     * Cypher query. The only thing that can be different in those two graphs are IDs of nodes and relationships.
     *
     * @param database        first graph, typically the one that has been created by some code that is being tested by this
     *                        method.
     * @param sameGraphCypher second graph expressed as a Cypher create statement, which communicates the desired state
     *                        of the database (first parameter) iff the code that created it is correct.
     * @throws AssertionError in case the graphs are not the same.
     */
    public static void assertSameGraph(GraphDatabaseService database, String sameGraphCypher) {
        GraphDatabaseService otherDatabase = new TestGraphDatabaseFactory().newImpermanentDatabase();

        new ExecutionEngine(otherDatabase).execute(sameGraphCypher);

        assertSameGraph(database, otherDatabase);
    }

    /**
     * Assert that the graph that would be created by the given Cypher query is a subgraph of the graph in the given
     * database. This means that every node and every relationship in the (Cypher) subgraph must be present in the
     * (database) graph.
     * <p/>
     * Nodes are considered equal if they have the exact same labels and properties. Relationships
     * are considered equal if they have the same type and properties. IDs of nodes and relationships are not taken
     * into account.
     * <p/>
     * This method is useful for testing that some portion of a graph has been created correctly without the need to
     * express the entire graph structure in Cypher.
     *
     * @param database       first graph, typically the one that has been created by some code that is being tested by
     *                       this method.
     * @param subgraphCypher second graph expressed as a Cypher create statement, which communicates the desired state
     *                       of the database (first parameter) iff the code that created it is correct.
     * @throws AssertionError in case the "cypher" graph is not a subgraph of the "database" graph.
     */
    public static void assertSubgraph(GraphDatabaseService database, String subgraphCypher) {
        GraphDatabaseService otherDatabase = new TestGraphDatabaseFactory().newImpermanentDatabase();

        new ExecutionEngine(otherDatabase).execute(subgraphCypher);

        assertSubgraph(database, otherDatabase);
    }

    private static void assertSameGraph(GraphDatabaseService database, GraphDatabaseService otherDatabase) {
        try (Transaction tx = database.beginTx()) {
            try (Transaction tx2 = otherDatabase.beginTx()) {
                assertSameNumbersOfElements(database, otherDatabase);
                doAssertSubgraph(database, otherDatabase);
                tx2.failure();
            }
            tx.failure();
        }
    }

    private static void assertSubgraph(GraphDatabaseService database, GraphDatabaseService otherDatabase) {
        try (Transaction tx = database.beginTx()) {
            try (Transaction tx2 = otherDatabase.beginTx()) {
                doAssertSubgraph(database, otherDatabase);
                tx2.failure();
            }
            tx.failure();
        }
    }

    private static void assertSameNumbersOfElements(GraphDatabaseService database, GraphDatabaseService otherDatabase) {
        assertEquals("There are different numbers of nodes in the two graphs", count(at(database).getAllNodes()), count(at(otherDatabase).getAllNodes()));
        assertEquals("There are different numbers of labels in the two graphs", count(at(database).getAllLabels()), count(at(otherDatabase).getAllLabels()));
        assertEquals("There are different numbers of property keys in the two graphs", count(at(database).getAllPropertyKeys()), count(at(otherDatabase).getAllPropertyKeys()));
        assertEquals("There are different numbers of relationships in the two graphs", count(at(database).getAllRelationships()), count(at(otherDatabase).getAllRelationships()));
        assertEquals("There are different numbers of relationship types in the two graphs", count(at(database).getAllRelationshipTypes()), count(at(otherDatabase).getAllRelationshipTypes()));
    }

    private static void doAssertSubgraph(GraphDatabaseService database, GraphDatabaseService otherDatabase) {
        Map<Long, Long[]> sameNodesMap = buildSameNodesMap(database, otherDatabase);
        Set<Map<Long, Long>> nodeMappings = buildNodeMappingPermutations(sameNodesMap);

        for (Map<Long, Long> nodeMapping : nodeMappings) {
            if (relationshipsMappingExists(database, otherDatabase, nodeMapping)) {
                return;
            }
        }

        fail("There is no corresponding relationship mapping for any of the possible node mappings");
    }

    private static Map<Long, Long[]> buildSameNodesMap(GraphDatabaseService database, GraphDatabaseService otherDatabase) {
        Map<Long, Long[]> sameNodesMap = new HashMap<>();

        for (Node node : at(otherDatabase).getAllNodes()) {
            Iterable<Node> sameNodes = findSameNodes(database, node);

            //fail fast
            if (!sameNodes.iterator().hasNext()) {
                fail("There is no corresponding node to " + node.toString());
            }

            Set<Long> sameNodeIds = new HashSet<>();
            for (Node sameNode : sameNodes) {
                sameNodeIds.add(sameNode.getId());
            }
            sameNodesMap.put(node.getId(), sameNodeIds.toArray(new Long[sameNodeIds.size()]));
        }

        return sameNodesMap;
    }

    private static Set<Map<Long, Long>> buildNodeMappingPermutations(Map<Long, Long[]> sameNodesMap) {
        Set<Map<Long, Long>> result = new HashSet<>();
        result.add(new HashMap<Long, Long>());

        for (Map.Entry<Long, Long[]> entry : sameNodesMap.entrySet()) {

            Set<Map<Long, Long>> newResult = new HashSet<>();

            for (Long target : entry.getValue()) {
                for (Map<Long, Long> mapping : result) {
                    if (!mapping.values().contains(target)) {
                        Map<Long, Long> newMapping = new HashMap<>(mapping);
                        newMapping.put(entry.getKey(), target);
                        newResult.add(newMapping);
                    }
                }
            }

            result = newResult;
        }

        return result;
    }

    private static boolean relationshipsMappingExists(GraphDatabaseService database, GraphDatabaseService otherDatabase, Map<Long, Long> mapping) {
        for (Relationship relationship : at(otherDatabase).getAllRelationships()) {
            if (!relationshipMappingExists(database, relationship, mapping)) {
                return false;
            }
        }

        return true;
    }

    private static boolean relationshipMappingExists(GraphDatabaseService database, Relationship relationship, Map<Long, Long> nodeMapping) {
        for (Relationship candidate : database.getNodeById(nodeMapping.get(relationship.getStartNode().getId())).getRelationships(OUTGOING)) {
            if (nodeMapping.get(relationship.getEndNode().getId()).equals(candidate.getEndNode().getId())) {
                if (areSame(candidate, relationship)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static Iterable<Node> findSameNodes(GraphDatabaseService database, Node node) {
        Iterator<Label> labels = node.getLabels().iterator();
        if (labels.hasNext()) {
            return findSameNodesByLabel(database, node, labels.next());
        }

        return findSameNodesWithoutLabel(database, node);
    }

    private static Iterable<Node> findSameNodesByLabel(GraphDatabaseService database, Node node, Label label) {
        Set<Node> result = new HashSet<>();

        for (Node candidate : GlobalGraphOperations.at(database).getAllNodesWithLabel(label)) {
            if (areSame(node, candidate)) {
                result.add(candidate);
            }
        }

        return result;
    }

    private static Iterable<Node> findSameNodesWithoutLabel(GraphDatabaseService database, Node node) {
        Set<Node> result = new HashSet<>();

        for (Node candidate : GlobalGraphOperations.at(database).getAllNodes()) {
            if (areSame(node, candidate)) {
                result.add(candidate);
            }
        }

        return result;
    }

    private static boolean areSame(Node node1, Node node2) {
        if (!haveSameLabels(node1, node2)) {
            return false;
        }

        if (!haveSameProperties(node1, node2)) {
            return false;
        }

        return true;
    }

    private static boolean areSame(Relationship relationship1, Relationship relationship2) {
        if (!haveSameType(relationship1, relationship2)) {
            return false;
        }

        if (!haveSameProperties(relationship1, relationship2)) {
            return false;
        }

        return true;
    }

    private static boolean haveSameLabels(Node node1, Node node2) {
        if (count(node1.getLabels()) != count(node2.getLabels())) {
            return false;
        }

        for (Label label : node1.getLabels()) {
            if (!node2.hasLabel(label)) {
                return false;
            }
        }

        return true;
    }

    private static boolean haveSameType(Relationship relationship1, Relationship relationship2) {
        return relationship1.isType(relationship2.getType());
    }

    private static boolean haveSameProperties(PropertyContainer pc1, PropertyContainer pc2) {
        if (count(pc1.getPropertyKeys()) != count(pc2.getPropertyKeys())) {
            return false;
        }

        for (String key : pc1.getPropertyKeys()) {
            if (!pc2.hasProperty(key) || !pc2.getProperty(key).equals(pc1.getProperty(key))) {
                return false;
            }
        }

        return true;
    }
}
