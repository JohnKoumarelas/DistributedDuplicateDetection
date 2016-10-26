package de.hpi.is.ddd.algorithms.bruteforce;

import de.hpi.is.ddd.evaluation.Evaluation;
import de.hpi.is.ddd.evaluation.Evaluator;
import de.hpi.is.idd.datasets.CoraUtility;
import de.hpi.is.idd.interfaces.DatasetUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Example Brute Force implementation for finding duplicates in the Apache Spark environment.
 *
 * Given the full dataset, tries to uniformly split the pair comparisons across the given nodes.
 *
 * Replicates all the data to all nodes.
 *
 */
public class ParallelBruteForce {
    private final static CSVFormat FORMAT = CSVFormat.TDF.withFirstRecordAsHeader();

    /* Number of nodes */
    private int n;

    /* Number of records */
    private int m;

    /* The whole set of records, that needs to be deduplicated */
    private List<Map<String, Object>> records;

    /* DatasetUtils of the current dataset */
    private DatasetUtils du;

    public ParallelBruteForce(int n, int m, List<Map<String, Object>> records, DatasetUtils du) {
        this.n = n;
        this.m = m;
        this.records = records;
        this.du = du;
    }

    public class PseudoNode {
        Integer nodeID;

        /* The whole set of records, that needs to be deduplicated */
        private List<Map<String, Object>> records;

        /* The records that are assigned to this node, to be checked to the remaining, to the right of them, records */
        private List<Integer> recordsAssigned;

        /* DatasetUtils of the current dataset */
        private DatasetUtils du;

        public PseudoNode(Integer nodeID, List<Map<String, Object>> records, List<Integer> recordsAssigned, DatasetUtils du) {
            this.nodeID = nodeID;
            this.records = records;
            this.recordsAssigned = recordsAssigned;
            this.du = du;
        }

        /**
         * Calculates the duplicates and writes them into HDFS.
         */
        public void deduplicate() {
            Set<Pair<String, String>> duplicates = new HashSet<>();
            for (Integer i: recordsAssigned) {
                for (int j = i + 1; j < records.size(); ++j) {
                    Double sim = du.calculateSimilarity(records.get(i), records.get(j), null);
                    if (sim >= du.getDatasetThreshold()) {
                        duplicates.add(Pair.of(String.valueOf(records.get(i).get("id")), String.valueOf(records.get(j).get("id"))));
                    }
                }
            }

            writeToHDFS(duplicates);
        }

        private Set<Pair<String, String>> duplicatesInHDFS;

        /**
         * Writes the given duplicates to HDFS.
         * @param duplicatesInHDFS
         */
        private void writeToHDFS(Set<Pair<String, String>> duplicatesInHDFS) {
            this.duplicatesInHDFS = duplicatesInHDFS;
        }

        /**
         * @return the current node's duplicates, from the HDFS.
         */
        public Set<Pair<String, String>> retrieveDuplicatesFromHDFS() {
            return duplicatesInHDFS;
        }
    }

    /**
     * Having a partitioning, replicates data across nodes, assigning them the pairs they have to check for
     *  similarities and finally collects and merges the results.
     */
    public Set<Pair<String, String>> deduplicate(Map<Integer, List<Integer>> partitioningPrecalculated) {
        Map<Integer, List<Integer>> partitioning = partitioningPrecalculated;
        if (partitioning == null) {
            partitioning = getPartitioning();
        }

        /* 1. Replicate data, 2. Send comparisons */
        Map<Integer, PseudoNode> nodes = new HashMap<>();
        for (int i = 0 ; i < n; ++i) {
            nodes.put(i, new PseudoNode(i, records, partitioning.get(i), du));
        }

        /* 3. Execute */
        nodes.entrySet().parallelStream().forEach(x -> x.getValue().deduplicate());

        /* 4. collect and merge results */
        List<Set<Pair<String, String>>> duplicatesLists = nodes.entrySet().parallelStream().map(x -> x.getValue().retrieveDuplicatesFromHDFS()).collect(Collectors.toList());
        Set<Pair<String, String>> duplicates = new HashSet<>();

        /* From each list, get the set x and from each set x add the pair y to the duplicates set */
        duplicatesLists.forEach(x -> x.forEach(duplicates::add));

        return duplicates;
    }

    /**
     *
     * Using a very simple technique partitions the comparisons across the nodes so that every node has close to the
     *   average number of comparisons to do.
     *
     *   TODO: Replace with a closed type, instead of the greedy calculation that takes place now.
     *
     * @return a partitioning of the comparisons across nodes.
     */
    public Map<Integer, List<Integer>> getPartitioning() {
        /* Total number of comparisons between all records */
        int totalCmp = (int) Math.floor(m * (m - 1) / 2.0);

        /* Average number of comparisons that a node has to do */
        int avgCmp = (int) Math.floor(totalCmp / n);
        System.out.println("Average number of comparisons: " + avgCmp);

        /* The final partitioning that will be returned */
        Map<Integer, List<Integer>> partitioning = new HashMap<>();

        /* We begin an iteration, so that when we cross the threshold of the average comparisons we move on to the next
         *  node.
         *
         *  Which records are assigned to this node, that have to be compared with everything on the right of them
         */
        List<Integer> recordsAssigned = new ArrayList<>();
        int nodeCmp = 0; // How many comparisons has the current node to do.
        for (int i = 0; i < m ; ++i) {  // Go through all the records.
            nodeCmp += m - (i + 1);  // Add the comparisons of the current record, with the right of it.
            recordsAssigned.add(i);  // This record is assigned to this node.

            /* Number of comparisons is greater or equal to the average.
             *  We clear everything and start preparing/counting for the next node.
             */
            if (nodeCmp >= avgCmp) {
                int nodeIdx = partitioning.size();
                partitioning.put(nodeIdx, recordsAssigned);
                System.out.println("Node: " + nodeIdx + " assigned: " + nodeCmp + " comparisons.");

                nodeCmp = 0;
                recordsAssigned = new ArrayList<>();
            }
        }
        if (nodeCmp > 0) { // Last node
            int nodeIdx = partitioning.size();
            partitioning.put(nodeIdx, recordsAssigned);
            System.out.println("Node: " + nodeIdx + " assigned: " + nodeCmp + " comparisons.");
        }
        return partitioning;
    }

    /**
     * Simple example that shows a dummy partitioning.
     *
     * @param args
     */
    public static void main(String[] args) {
        Map<String, String> argsMap = new HashMap<>();
        for (String arg : args) {
            String[] toks = arg.split("=");
            argsMap.put(toks[0], toks[1]);
        }

        int n = 16; // Number of nodes
        if (argsMap.containsKey("n")) {
            n = Integer.parseInt(argsMap.get("n"));
        }

        File dataset = new File("/data/datasets/incremental_duplicate_detection/cora/cora_v3.tsv");
        if (argsMap.containsKey("dataset")) {
            dataset = new File(argsMap.get("dataset"));
        }
        DatasetUtils du;

        File goldStandard = new File("/data/datasets/incremental_duplicate_detection/cora/cora_ground_truth.tsv");
        if (argsMap.containsKey("goldStandard")) {
            goldStandard = new File(argsMap.get("goldStandard"));
        }
        Evaluator evaluator = new Evaluator(goldStandard);

        List<Map<String, Object>> records = new ArrayList<>();

        try (CSVParser parser = FORMAT.parse(new InputStreamReader(new FileInputStream(dataset)))) {
            du = new CoraUtility();
            for (CSVRecord record : parser) {
                Map<String, Object> rec = du.parseRecord(record.toMap());
                records.add(rec);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error while parsing the dataset file", e);
        }

        ParallelBruteForce pbf = new ParallelBruteForce(n, records.size(), records, du);
        Map<Integer, List<Integer>> partitioning = pbf.getPartitioning();
        System.out.println("Partitioning; " + partitioning);

        Set<Pair<String, String>> duplicates = pbf.deduplicate(partitioning);
        System.out.println("Duplicates (pairs): " + duplicates.size());

        Evaluation evaluation = evaluator.evaluate(duplicates);
        System.out.println("Evaluation: " + evaluation);
    }

}
