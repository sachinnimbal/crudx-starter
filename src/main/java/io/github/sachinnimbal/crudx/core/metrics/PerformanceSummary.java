package io.github.sachinnimbal.crudx.core.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;


/**
 * @author Sachin Nimbal
 * @version 1.0.0-SNAPSHOT
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceSummary {
    private long totalRequests;
    private long successfulRequests;
    private long failedRequests;
    private double successRate;
    private long totalExecutionTimeMs;
    private double avgExecutionTimeMs;
    private long minExecutionTimeMs;
    private long maxExecutionTimeMs;

    // Memory metrics
    private Long avgMemoryKb;
    private Long minMemoryKb;
    private Long maxMemoryKb;
    private Long totalMemoryKb;

    private LocalDateTime monitoringStartTime;
    private LocalDateTime lastRequestTime;
    private Map<String, EndpointStats> endpointStats;
    private Map<String, Long> topSlowEndpoints;
    private Map<String, Long> topErrorEndpoints;
    private Map<String, Long> topMemoryEndpoints; // NEW: Top 5 memory-intensive endpoints
}

//    private String getDashboardHtml() {
//        String apiBase = properties.getDashboardPath();
//
//        return "<!DOCTYPE html>\n" +
//                "<html lang=\"en\">\n" +
//                "<head>\n" +
//                "    <meta charset=\"UTF-8\">\n" +
//                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
//                "    <title>CrudX Performance Monitor</title>\n" +
//                "    <style>\n" +
//                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
//                "        body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif; background: #0f172a; color: #e2e8f0; min-height: 100vh; }\n" +
//                "        .header { background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%); padding: 2rem; box-shadow: 0 4px 20px rgba(99,102,241,0.3); }\n" +
//                "        .header h1 { font-size: 2rem; font-weight: 700; color: white; margin-bottom: 0.5rem; }\n" +
//                "        .header p { color: rgba(255,255,255,0.8); font-size: 0.95rem; }\n" +
//                "        .live-indicator { display: inline-flex; align-items: center; gap: 8px; background: rgba(34,197,94,0.2); padding: 6px 14px; border-radius: 20px; font-size: 0.85rem; margin-top: 10px; }\n" +
//                "        .live-dot { width: 8px; height: 8px; background: #22c55e; border-radius: 50%; animation: pulse 2s infinite; }\n" +
//                "        @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }\n" +
//                "        .container { max-width: 1600px; margin: 0 auto; padding: 2rem; }\n" +
//                "        .metrics-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1.5rem; margin-bottom: 2rem; }\n" +
//                "        .metric-card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 1.5rem; transition: all 0.3s; position: relative; overflow: hidden; }\n" +
//                "        .metric-card:hover { transform: translateY(-4px); border-color: #6366f1; box-shadow: 0 8px 24px rgba(99,102,241,0.2); }\n" +
//                "        .metric-card::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, #6366f1, #8b5cf6); }\n" +
//                "        .metric-label { font-size: 0.85rem; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 0.75rem; font-weight: 600; }\n" +
//                "        .metric-value { font-size: 2.5rem; font-weight: 700; color: #e2e8f0; line-height: 1; }\n" +
//                "        .metric-unit { font-size: 1rem; color: #64748b; font-weight: 400; margin-left: 6px; }\n" +
//                "        .metric-change { font-size: 0.85rem; margin-top: 0.5rem; display: flex; align-items: center; gap: 4px; }\n" +
//                "        .metric-change.up { color: #22c55e; }\n" +
//                "        .metric-change.down { color: #ef4444; }\n" +
//                "        .progress-bar { width: 100%; height: 8px; background: #334155; border-radius: 4px; margin-top: 1rem; overflow: hidden; }\n" +
//                "        .progress-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; background: linear-gradient(90deg, #22c55e, #10b981); }\n" +
//                "        .chart-card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 1.5rem; margin-bottom: 2rem; }\n" +
//                "        .chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1.5rem; }\n" +
//                "        .chart-title { font-size: 1.25rem; font-weight: 600; color: #e2e8f0; }\n" +
//                "        .chart-subtitle { font-size: 0.85rem; color: #64748b; }\n" +
//                "        .endpoint-item { padding: 1rem; border-bottom: 1px solid #334155; display: flex; justify-content: space-between; align-items: center; transition: background 0.2s; }\n" +
//                "        .endpoint-item:hover { background: rgba(99,102,241,0.05); }\n" +
//                "        .endpoint-item:last-child { border-bottom: none; }\n" +
//                "        .method-badge { display: inline-flex; align-items: center; justify-content: center; padding: 4px 10px; border-radius: 6px; font-size: 0.75rem; font-weight: 700; margin-right: 12px; }\n" +
//                "        .method-GET { background: rgba(34,197,94,0.2); color: #22c55e; border: 1px solid rgba(34,197,94,0.3); }\n" +
//                "        .method-POST { background: rgba(59,130,246,0.2); color: #3b82f6; border: 1px solid rgba(59,130,246,0.3); }\n" +
//                "        .method-PUT { background: rgba(251,191,36,0.2); color: #fbbf24; border: 1px solid rgba(251,191,36,0.3); }\n" +
//                "        .method-PATCH { background: rgba(139,92,246,0.2); color: #8b5cf6; border: 1px solid rgba(139,92,246,0.3); }\n" +
//                "        .method-DELETE { background: rgba(239,68,68,0.2); color: #ef4444; border: 1px solid rgba(239,68,68,0.3); }\n" +
//                "        .endpoint-path { color: #e2e8f0; font-weight: 500; font-size: 0.95rem; }\n" +
//                "        .endpoint-entity { color: #64748b; font-size: 0.85rem; margin-left: 8px; }\n" +
//                "        .endpoint-metrics { display: flex; gap: 1.5rem; align-items: center; flex-wrap: wrap; }\n" +
//                "        .metric-badge { display: flex; flex-direction: column; align-items: flex-end; }\n" +
//                "        .metric-badge-value { font-size: 1.1rem; font-weight: 600; color: #e2e8f0; }\n" +
//                "        .metric-badge-label { font-size: 0.75rem; color: #64748b; }\n" +
//                "        .controls { display: flex; gap: 1rem; margin-bottom: 2rem; flex-wrap: wrap; }\n" +
//                "        button { background: linear-gradient(135deg, #6366f1, #8b5cf6); color: white; border: none; padding: 0.75rem 1.5rem; border-radius: 8px; cursor: pointer; font-size: 0.95rem; font-weight: 600; transition: all 0.3s; box-shadow: 0 4px 12px rgba(99,102,241,0.3); }\n" +
//                "        button:hover { transform: translateY(-2px); box-shadow: 0 6px 20px rgba(99,102,241,0.4); }\n" +
//                "        button.secondary { background: #334155; box-shadow: none; }\n" +
//                "        button.secondary:hover { background: #475569; }\n" +
//                "        button.danger { background: linear-gradient(135deg, #ef4444, #dc2626); box-shadow: 0 4px 12px rgba(239,68,68,0.3); }\n" +
//                "        button.danger:hover { box-shadow: 0 6px 20px rgba(239,68,68,0.4); }\n" +
//                "        .loading { text-align: center; padding: 4rem; color: #94a3b8; font-size: 1.1rem; }\n" +
//                "        .loading::after { content: '...'; animation: dots 1.5s steps(3, end) infinite; }\n" +
//                "        @keyframes dots { 0%, 20% { content: '.'; } 40% { content: '..'; } 60%, 100% { content: '...'; } }\n" +
//                "        .error-banner { background: rgba(239,68,68,0.1); border: 1px solid rgba(239,68,68,0.3); color: #fca5a5; padding: 1rem 1.5rem; border-radius: 8px; margin-bottom: 2rem; display: flex; align-items: center; gap: 12px; }\n" +
//                "        .stats-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 1.5rem; margin-bottom: 2rem; }\n" +
//                "        .mini-chart { background: #0f172a; padding: 1rem; border-radius: 8px; margin-top: 1rem; }\n" +
//                "        .response-time-indicator { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; }\n" +
//                "        .fast { background: #22c55e; }\n" +
//                "        .medium { background: #fbbf24; }\n" +
//                "        .slow { background: #ef4444; }\n" +
//                "        .scrollable { max-height: 500px; overflow-y: auto; }\n" +
//                "        .scrollable::-webkit-scrollbar { width: 8px; }\n" +
//                "        .scrollable::-webkit-scrollbar-track { background: #0f172a; border-radius: 4px; }\n" +
//                "        .scrollable::-webkit-scrollbar-thumb { background: #334155; border-radius: 4px; }\n" +
//                "        .scrollable::-webkit-scrollbar-thumb:hover { background: #475569; }\n" +
//                "        .empty-state { text-align: center; padding: 3rem; color: #64748b; }\n" +
//                "        .empty-state-icon { font-size: 3rem; margin-bottom: 1rem; opacity: 0.3; }\n" +
//                "        .timestamp { font-size: 0.85rem; color: #64748b; margin-top: 0.5rem; }\n" +
//                "        .memory-indicator { display: inline-block; padding: 2px 6px; border-radius: 4px; font-size: 0.7rem; margin-left: 4px; }\n" +
//                "        .memory-low { background: rgba(34,197,94,0.2); color: #22c55e; }\n" +
//                "        .memory-medium { background: rgba(251,191,36,0.2); color: #fbbf24; }\n" +
//                "        .memory-high { background: rgba(239,68,68,0.2); color: #ef4444; }\n" +
//                "    </style>\n" +
//                "</head>\n" +
//                "<body>\n" +
//                "    <div class=\"header\">\n" +
//                "        <h1>CrudX Performance Monitor</h1>\n" +
//                "        <p>Real-time monitoring and analytics for your API endpoints</p>\n" +
//                "        <div class=\"live-indicator\"><div class=\"live-dot\"></div>LIVE</div>\n" +
//                "    </div>\n" +
//                "    <div class=\"container\">\n" +
//                "        <div class=\"controls\">\n" +
//                "            <button onclick=\"loadData()\">↻ Refresh Now</button>\n" +
//                "            <button class=\"secondary\" onclick=\"toggleAutoRefresh()\"><span id=\"autoRefreshText\">⏸ Pause Auto-Refresh</span></button>\n" +
//                "            <button class=\"danger\" onclick=\"clearMetrics()\">🗑 Clear All Metrics</button>\n" +
//                "            <div style=\"flex: 1\"></div>\n" +
//                "            <div class=\"timestamp\">Last updated: <span id=\"lastUpdate\">-</span></div>\n" +
//                "        </div>\n" +
//                "        <div id=\"error\" class=\"error-banner\" style=\"display: none;\">⚠ <span id=\"errorText\"></span></div>\n" +
//                "        <div id=\"loading\" class=\"loading\">Loading performance data</div>\n" +
//                "        <div id=\"content\" style=\"display: none;\">\n" +
//                "            <div class=\"metrics-grid\">\n" +
//                "                <div class=\"metric-card\">\n" +
//                "                    <div class=\"metric-label\">Total Requests</div>\n" +
//                "                    <div class=\"metric-value\" id=\"totalRequests\">0</div>\n" +
//                "                    <div class=\"metric-change\" id=\"requestsChange\"></div>\n" +
//                "                </div>\n" +
//                "                <div class=\"metric-card\">\n" +
//                "                    <div class=\"metric-label\">Success Rate</div>\n" +
//                "                    <div class=\"metric-value\" id=\"successRate\">0<span class=\"metric-unit\">%</span></div>\n" +
//                "                    <div class=\"progress-bar\"><div class=\"progress-fill\" id=\"successBar\" style=\"width: 0%\"></div></div>\n" +
//                "                </div>\n" +
//                "                <div class=\"metric-card\">\n" +
//                "                    <div class=\"metric-label\">Avg Response Time</div>\n" +
//                "                    <div class=\"metric-value\" id=\"avgTime\">0<span class=\"metric-unit\">ms</span></div>\n" +
//                "                    <div class=\"metric-change\" id=\"timeChange\"></div>\n" +
//                "                </div>\n" +
//                "                <div class=\"metric-card\">\n" +
//                "                    <div class=\"metric-label\">Avg Memory Usage</div>\n" +
//                "                    <div class=\"metric-value\" id=\"avgMemory\">0<span class=\"metric-unit\">KB</span></div>\n" +
//                "                    <div class=\"metric-change\" id=\"memoryChange\"></div>\n" +
//                "                </div>\n" +
//                "            </div>\n" +
//                "            <div class=\"stats-row\">\n" +
//                "                <div class=\"chart-card\">\n" +
//                "                    <div class=\"chart-header\"><div><div class=\"chart-title\">Response Times</div><div class=\"chart-subtitle\">Performance breakdown</div></div></div>\n" +
//                "                    <div style=\"display: flex; gap: 2rem; margin-top: 1rem;\">\n" +
//                "                        <div><div style=\"color: #64748b; font-size: 0.85rem;\">Min</div><div style=\"font-size: 1.5rem; font-weight: 600; color: #22c55e;\" id=\"minTime\">0ms</div></div>\n" +
//                "                        <div><div style=\"color: #64748b; font-size: 0.85rem;\">Avg</div><div style=\"font-size: 1.5rem; font-weight: 600; color: #3b82f6;\" id=\"avgTime2\">0ms</div></div>\n" +
//                "                        <div><div style=\"color: #64748b; font-size: 0.85rem;\">Max</div><div style=\"font-size: 1.5rem; font-weight: 600; color: #ef4444;\" id=\"maxTime\">0ms</div></div>\n" +
//                "                    </div>\n" +
//                "                </div>\n" +
//                "                <div class=\"chart-card\">\n" +
//                "                    <div class=\"chart-header\"><div><div class=\"chart-title\">Request Status</div><div class=\"chart-subtitle\">Success vs Failed</div></div></div>\n" +
//                "                    <div style=\"display: flex; gap: 2rem; margin-top: 1rem;\">\n" +
//                "                        <div><div style=\"color: #64748b; font-size: 0.85rem;\">Successful</div><div style=\"font-size: 1.5rem; font-weight: 600; color: #22c55e;\" id=\"successCount\">0</div></div>\n" +
//                "                        <div><div style=\"color: #64748b; font-size: 0.85rem;\">Failed</div><div style=\"font-size: 1.5rem; font-weight: 600; color: #ef4444;\" id=\"failedCount\">0</div></div>\n" +
//                "                    </div>\n" +
//                "                </div>\n" +
//                "            </div>\n" +
//                "            <div class=\"chart-card\">\n" +
//                "                <div class=\"chart-header\"><div class=\"chart-title\">Endpoint Performance</div><div class=\"chart-subtitle\">Real-time metrics per endpoint</div></div>\n" +
//                "                <div class=\"scrollable\" id=\"endpointList\"></div>\n" +
//                "            </div>\n" +
//                "            <div class=\"stats-row\">\n" +
//                "                <div class=\"chart-card\">\n" +
//                "                    <div class=\"chart-header\"><div class=\"chart-title\">Slowest Endpoints</div><div class=\"chart-subtitle\">Top 5 by max response time</div></div>\n" +
//                "                    <div id=\"slowEndpoints\"></div>\n" +
//                "                </div>\n" +
//                "                <div class=\"chart-card\">\n" +
//                "                    <div class=\"chart-header\"><div class=\"chart-title\">Memory Intensive Endpoints</div><div class=\"chart-subtitle\">Highest memory usage</div></div>\n" +
//                "                    <div id=\"memoryEndpoints\"></div>\n" +
//                "                </div>\n" +
//                "            </div>\n" +
//                "        </div>\n" +
//                "    </div>\n" +
//                "    <script>\n" +
//                "        const API_BASE = '" + apiBase + "';\n" +
//                "        let autoRefresh = true;\n" +
//                "        let refreshInterval;\n" +
//                "        let previousData = {};\n" +
//                "        \n" +
//                "        function formatMemory(kb) {\n" +
//                "            if (!kb && kb !== 0) return 'N/A';\n" +
//                "            if (kb < 1024) {\n" +
//                "                return kb.toFixed(0) + ' KB';\n" +
//                "            } else {\n" +
//                "                const mb = (kb / 1024).toFixed(2);\n" +
//                "                return kb.toFixed(0) + ' KB (' + mb + ' MB)';\n" +
//                "            }\n" +
//                "        }\n" +
//                "        \n" +
//                "        function getMemoryClass(kb) {\n" +
//                "            if (!kb) return '';\n" +
//                "            if (kb < 1024) return 'memory-low';\n" +
//                "            if (kb < 5120) return 'memory-medium';\n" +
//                "            return 'memory-high';\n" +
//                "        }\n" +
//                "        \n" +
//                "        async function loadData() {\n" +
//                "            try {\n" +
//                "                const response = await fetch(`${API_BASE}/summary`);\n" +
//                "                const result = await response.json();\n" +
//                "                if (result.success) {\n" +
//                "                    updateDashboard(result.data);\n" +
//                "                    document.getElementById('loading').style.display = 'none';\n" +
//                "                    document.getElementById('content').style.display = 'block';\n" +
//                "                    document.getElementById('error').style.display = 'none';\n" +
//                "                    document.getElementById('lastUpdate').textContent = new Date().toLocaleTimeString();\n" +
//                "                } else { showError('Failed to load data'); }\n" +
//                "            } catch (error) { showError('Connection error: ' + error.message); }\n" +
//                "        }\n" +
//                "        \n" +
//                "        function updateDashboard(data) {\n" +
//                "            const totalReqs = data.totalRequests || 0;\n" +
//                "            const prevTotal = previousData.totalRequests || 0;\n" +
//                "            document.getElementById('totalRequests').textContent = totalReqs.toLocaleString();\n" +
//                "            if (prevTotal > 0) {\n" +
//                "                const change = totalReqs - prevTotal;\n" +
//                "                if (change > 0) document.getElementById('requestsChange').innerHTML = '<span class=\"up\">↑ +' + change + ' new</span>';\n" +
//                "            }\n" +
//                "            \n" +
//                "            const successRate = data.successRate || 0;\n" +
//                "            document.getElementById('successRate').innerHTML = successRate.toFixed(1) + '<span class=\"metric-unit\">%</span>';\n" +
//                "            document.getElementById('successBar').style.width = successRate + '%';\n" +
//                "            \n" +
//                "            const avgTime = data.avgExecutionTimeMs || 0;\n" +
//                "            document.getElementById('avgTime').innerHTML = avgTime.toFixed(0) + '<span class=\"metric-unit\">ms</span>';\n" +
//                "            document.getElementById('avgTime2').textContent = avgTime.toFixed(0) + 'ms';\n" +
//                "            const prevAvg = previousData.avgExecutionTimeMs || 0;\n" +
//                "            if (prevAvg > 0) {\n" +
//                "                const diff = avgTime - prevAvg;\n" +
//                "                if (diff > 0) document.getElementById('timeChange').innerHTML = '<span class=\"down\">↑ +' + diff.toFixed(0) + 'ms slower</span>';\n" +
//                "                else if (diff < 0) document.getElementById('timeChange').innerHTML = '<span class=\"up\">↓ ' + Math.abs(diff).toFixed(0) + 'ms faster</span>';\n" +
//                "            }\n" +
//                "            \n" +
//                "            // Memory metrics\n" +
//                "            const avgMemory = data.avgMemoryKb || 0;\n" +
//                "            const memoryFormatted = formatMemory(avgMemory);\n" +
//                "            const memParts = memoryFormatted.split(' ');\n" +
//                "            document.getElementById('avgMemory').innerHTML = memParts[0] + '<span class=\"metric-unit\">' + memParts[1] + (memParts[2] ? ' ' + memParts[2] + ' ' + memParts[3] : '') + '</span>';\n" +
//                "            \n" +
//                "            document.getElementById('minTime').textContent = (data.minExecutionTimeMs || 0) + 'ms';\n" +
//                "            document.getElementById('maxTime').textContent = (data.maxExecutionTimeMs || 0) + 'ms';\n" +
//                "            document.getElementById('successCount').textContent = (data.successfulRequests || 0).toLocaleString();\n" +
//                "            document.getElementById('failedCount').textContent = (data.failedRequests || 0).toLocaleString();\n" +
//                "            \n" +
//                "            const endpointList = document.getElementById('endpointList');\n" +
//                "            endpointList.innerHTML = '';\n" +
//                "            if (data.endpointStats && Object.keys(data.endpointStats).length > 0) {\n" +
//                "                Object.entries(data.endpointStats).forEach(([key, stats]) => {\n" +
//                "                    const speedClass = stats.avgExecutionTimeMs < 50 ? 'fast' : stats.avgExecutionTimeMs < 200 ? 'medium' : 'slow';\n" +
//                "                    const item = document.createElement('div');\n" +
//                "                    item.className = 'endpoint-item';\n" +
//                "                    \n" +
//                "                    let metricsHtml = `\n" +
//                "                        <div class=\"endpoint-info\">\n" +
//                "                            <span class=\"method-badge method-${stats.method}\">${stats.method}</span>\n" +
//                "                            <span class=\"response-time-indicator ${speedClass}\"></span>\n" +
//                "                            <span class=\"endpoint-path\">${stats.endpoint}</span>\n" +
//                "                            ${stats.entityName ? `<span class=\"endpoint-entity\">${stats.entityName}</span>` : ''}\n" +
//                "                        </div>\n" +
//                "                        <div class=\"endpoint-metrics\">\n" +
//                "                            <div class=\"metric-badge\">\n" +
//                "                                <div class=\"metric-badge-value\">${stats.totalCalls}</div>\n" +
//                "                                <div class=\"metric-badge-label\">calls</div>\n" +
//                "                            </div>\n" +
//                "                            <div class=\"metric-badge\">\n" +
//                "                                <div class=\"metric-badge-value\">${stats.avgExecutionTimeMs.toFixed(0)}ms</div>\n" +
//                "                                <div class=\"metric-badge-label\">avg time</div>\n" +
//                "                            </div>\n" +
//                "                            <div class=\"metric-badge\">\n" +
//                "                                <div class=\"metric-badge-value\">${stats.maxExecutionTimeMs}ms</div>\n" +
//                "                                <div class=\"metric-badge-label\">max time</div>\n" +
//                "                            </div>`;\n" +
//                "                    \n" +
//                "                    if (stats.avgMemoryKb) {\n" +
//                "                        const memClass = getMemoryClass(stats.avgMemoryKb);\n" +
//                "                        metricsHtml += `\n" +
//                "                          <div class=\"metric-badge\">\n" +
//                "                            <div class=\"metric-badge-value\"><span class=\"memory-indicator ${memClass}\">${formatMemory(stats.avgMemoryKb)}</span></div>\n" +
//                "                                <div class=\"metric-badge-label\">avg memory</div>\n" +
//                "                            </div>`;\n" +
//                "                    }\n" +
//                "                    \n" +
//                "                    if (stats.failedCalls > 0) {\n" +
//                "                        metricsHtml += `\n" +
//                "                            <div class=\"metric-badge\">\n" +
//                "                                <div class=\"metric-badge-value\" style=\"color: #ef4444;\">${stats.failedCalls}</div>\n" +
//                "                                <div class=\"metric-badge-label\">errors</div>\n" +
//                "                            </div>`;\n" +
//                "                    }\n" +
//                "                    \n" +
//                "                    metricsHtml += `</div>`;\n" +
//                "                    item.innerHTML = metricsHtml;\n" +
//                "                    endpointList.appendChild(item);\n" +
//                "                });\n" +
//                "            } else {\n" +
//                "                endpointList.innerHTML = '<div class=\"empty-state\"><div class=\"empty-state-icon\">📊</div><div>No endpoint data yet. Make some API requests to see metrics.</div></div>';\n" +
//                "            }\n" +
//                "            \n" +
//                "            const slowEndpoints = document.getElementById('slowEndpoints');\n" +
//                "            slowEndpoints.innerHTML = '';\n" +
//                "            if (data.topSlowEndpoints && Object.keys(data.topSlowEndpoints).length > 0) {\n" +
//                "                Object.entries(data.topSlowEndpoints).forEach(([endpoint, time], idx) => {\n" +
//                "                    slowEndpoints.innerHTML += `<div style=\"padding: 1rem; border-bottom: 1px solid #334155; display: flex; justify-content: space-between;\"><div><span style=\"color: #64748b; margin-right: 12px;\">#${idx + 1}</span><span style=\"color: #e2e8f0;\">${endpoint}</span></div><div style=\"font-weight: 600; color: #ef4444;\">${time}ms</div></div>`;\n" +
//                "                });\n" +
//                "            } else {\n" +
//                "                slowEndpoints.innerHTML = '<div class=\"empty-state\"><div class=\"empty-state-icon\">✓</div><div>No slow endpoints detected</div></div>';\n" +
//                "            }\n" +
//                "            \n" +
//                "            const memoryEndpoints = document.getElementById('memoryEndpoints');\n" +
//                "            memoryEndpoints.innerHTML = '';\n" +
//                "            if (data.topMemoryEndpoints && Object.keys(data.topMemoryEndpoints).length > 0) {\n" +
//                "                Object.entries(data.topMemoryEndpoints).forEach(([endpoint, memKb], idx) => {\n" +
//                "                    const memClass = getMemoryClass(memKb);\n" +
//                "                    memoryEndpoints.innerHTML += `<div style=\"padding: 1rem; border-bottom: 1px solid #334155; display: flex; justify-content: space-between;\"><div><span style=\"color: #64748b; margin-right: 12px;\">#${idx + 1}</span><span style=\"color: #e2e8f0;\">${endpoint}</span></div><div style=\"font-weight: 600;\" class=\"memory-indicator ${memClass}\">${formatMemory(memKb)}</div></div>`;\n" +
//                "                });\n" +
//                "            } else {\n" +
//                "                memoryEndpoints.innerHTML = '<div class=\"empty-state\"><div class=\"empty-state-icon\">✓</div><div>No memory data available</div></div>';\n" +
//                "            }\n" +
//                "            \n" +
//                "            previousData = data;\n" +
//                "        }\n" +
//                "        \n" +
//                "        async function clearMetrics() {\n" +
//                "            if (!confirm('Clear all performance metrics? This cannot be undone.')) return;\n" +
//                "            try {\n" +
//                "                const response = await fetch(`${API_BASE}/metrics`, { method: 'DELETE' });\n" +
//                "                const result = await response.json();\n" +
//                "                if (result.success) { previousData = {}; loadData(); }\n" +
//                "                else { showError('Failed to clear metrics'); }\n" +
//                "            } catch (error) { showError('Error: ' + error.message); }\n" +
//                "        }\n" +
//                "        \n" +
//                "        function toggleAutoRefresh() {\n" +
//                "            autoRefresh = !autoRefresh;\n" +
//                "            const btn = document.getElementById('autoRefreshText');\n" +
//                "            if (autoRefresh) { btn.textContent = '⏸ Pause Auto-Refresh'; startAutoRefresh(); }\n" +
//                "            else { btn.textContent = '▶ Resume Auto-Refresh'; stopAutoRefresh(); }\n" +
//                "        }\n" +
//                "        \n" +
//                "        function startAutoRefresh() { refreshInterval = setInterval(loadData, 5000); }\n" +
//                "        function stopAutoRefresh() { if (refreshInterval) clearInterval(refreshInterval); }\n" +
//                "        \n" +
//                "        function showError(message) {\n" +
//                "            document.getElementById('errorText').textContent = message;\n" +
//                "            document.getElementById('error').style.display = 'flex';\n" +
//                "            document.getElementById('loading').style.display = 'none';\n" +
//                "        }\n" +
//                "        \n" +
//                "        loadData();\n" +
//                "        startAutoRefresh();\n" +
//                "    </script>\n" +
//                "</body>\n" +
//                "</html>";
//    }