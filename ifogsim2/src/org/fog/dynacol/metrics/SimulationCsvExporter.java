package org.fog.dynacol.metrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class SimulationCsvExporter {

    private static final String HEADER =
            "method,policy,nodes,scenario,trial,seed,p95_ms,mean_loop_ms,sla_pct,overhead_norm,"
                    + "control_messages,colonies,variant,mean_energy_per_req,total_cost";

    private SimulationCsvExporter() {
    }

    public static void append(Path csvPath, SimulationMetrics metrics) throws IOException {
        Files.createDirectories(csvPath.getParent());
        try (FileChannel channel = FileChannel.open(
                csvPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
             FileLock lock = channel.lock()) {
            boolean writeHeader = channel.size() == 0;
            BufferedWriter writer = new BufferedWriter(
                    java.nio.channels.Channels.newWriter(channel, StandardCharsets.UTF_8));
            if (writeHeader) {
                writer.write(HEADER);
                writer.newLine();
            }
            writer.write(String.format(
                    "%s,%s,%d,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%s,%.6f,%.6f",
                    csvQuote(metrics.getMethod()),
                    csvQuote(metrics.getPolicy()),
                    metrics.getNodes(),
                    csvQuote(metrics.getScenario()),
                    metrics.getTrial(),
                    metrics.getSeed(),
                    metrics.getP95Ms(),
                    metrics.getMeanLoopMs(),
                    metrics.getSlaViolationPct(),
                    metrics.getOverheadNorm(),
                    metrics.getControlMessages(),
                    metrics.getColonies(),
                    csvQuote(metrics.getVariant()),
                    metrics.getMeanEnergyPerRequest(),
                    metrics.getTotalCost()
            ));
            writer.newLine();
            writer.flush();
        }
    }

    public static void printJson(SimulationMetrics metrics) {
        System.out.printf(
                "METRICS_JSON {\"method\":\"%s\",\"policy\":\"%s\",\"nodes\":%d,\"scenario\":\"%s\","
                        + "\"trial\":%d,\"seed\":%d,\"p95_ms\":%.3f,\"mean_loop_ms\":%.3f,"
                        + "\"sla_pct\":%.3f,\"overhead_norm\":%.3f,\"control_messages\":%d,\"colonies\":%d,"
                        + "\"variant\":\"%s\",\"mean_energy_per_req\":%.6f,\"total_cost\":%.6f}%n",
                metrics.getMethod(), metrics.getPolicy(), metrics.getNodes(), metrics.getScenario(),
                metrics.getTrial(), metrics.getSeed(), metrics.getP95Ms(), metrics.getMeanLoopMs(),
                metrics.getSlaViolationPct(), metrics.getOverheadNorm(),
                metrics.getControlMessages(), metrics.getColonies(), metrics.getVariant(),
                metrics.getMeanEnergyPerRequest(), metrics.getTotalCost()
        );
    }

    private static String csvQuote(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
