package io.github.sachinnimbal.crudx.web;

import io.github.sachinnimbal.crudx.core.config.CrudXPerformanceProperties;
import io.github.sachinnimbal.crudx.core.config.CrudxMetadataProperties;
import io.github.sachinnimbal.crudx.core.metrics.CrudXPerformanceTracker;
import io.github.sachinnimbal.crudx.core.metrics.PerformanceMetric;
import io.github.sachinnimbal.crudx.core.metrics.PerformanceSummary;
import io.github.sachinnimbal.crudx.core.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * @author Sachin Nimbal
 * @version 1.0.0-SNAPSHOT
 * @since 2025
 * @Contact: <a href="mailto:sachinnimbal9@gmail.com">sachinnimbal9@gmail.com</a>
 * @see <a href="https://www.linkedin.com/in/sachin-nimbal/">LinkedIn Profile</a>
 */
@Slf4j
@RestController
@RequestMapping("${crudx.performance.dashboard-path:/crudx/performance}")
@ConditionalOnProperty(prefix = "crudx.performance", name = "enabled", havingValue = "true")
public class CrudXPerformanceController {

    private final CrudXPerformanceTracker tracker;
    private final CrudXPerformanceProperties properties;
    @Autowired
    private CrudxMetadataProperties metadataProperties;
    public CrudXPerformanceController(CrudXPerformanceTracker tracker, CrudXPerformanceProperties properties) {
        this.tracker = tracker;
        this.properties = properties;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<PerformanceSummary>> getSummary() {
        PerformanceSummary summary = tracker.getSummary();
        return ResponseEntity.ok(ApiResponse.success(summary, "Performance summary retrieved"));
    }

    @GetMapping("/metrics")
    public ResponseEntity<ApiResponse<List<PerformanceMetric>>> getMetrics() {
        List<PerformanceMetric> metrics = tracker.getMetrics();
        return ResponseEntity.ok(ApiResponse.success(metrics,
                String.format("Retrieved %d metrics", metrics.size())));
    }

    @GetMapping("/metrics/endpoint")
    public ResponseEntity<ApiResponse<List<PerformanceMetric>>> getMetricsByEndpoint(
            @RequestParam String endpoint) {
        List<PerformanceMetric> metrics = tracker.getMetricsByEndpoint(endpoint);
        return ResponseEntity.ok(ApiResponse.success(metrics,
                String.format("Retrieved %d metrics for endpoint: %s", metrics.size(), endpoint)));
    }

    @DeleteMapping("/metrics")
    public ResponseEntity<ApiResponse<Void>> clearMetrics() {
        tracker.clearMetrics();
        return ResponseEntity.ok(ApiResponse.success(null, "All metrics cleared"));
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<CrudXPerformanceProperties>> getConfig() {
        return ResponseEntity.ok(ApiResponse.success(properties, "Performance configuration"));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("OK", "Performance monitoring is active"));
    }

    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> dashboard() throws IOException {
        Resource resource = new ClassPathResource("index.html");
        String html = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Replace placeholder with actual API path
        html = html.replace("${API_BASE}", properties.getDashboardPath());

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @GetMapping("/metadata")
    public ResponseEntity<?> getMetadata() {
        CrudxMetadataProperties.Author author = metadataProperties.getAuthor();
        CrudxMetadataProperties.Project project = metadataProperties.getProject();
        Map<String, Object> metadata = new HashMap<>();
        // Author Data
        metadata.put("authorName", author.getName());
        metadata.put("authorEmail", author.getEmail());
        metadata.put("authorLinkedin", author.getLinkedin());
        metadata.put("since", author.getSince());
        metadata.put("version", author.getVersion());

        // Project Data
        metadata.put("group", project.getGroup());
        metadata.put("artifact", project.getArtifact());
        metadata.put("projectVersion", author.getVersion()); // Using author's version for project version
        return ResponseEntity.ok(ApiResponse.success(metadata, "Metadata retrieved"));
    }

    private String getDashboardHtml() {
        String apiBase = properties.getDashboardPath();

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>CrudX Performance Analytics</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0"></script>
                    <style>
                        @keyframes pulse-dot { 0%%, 100%% { opacity: 1; } 50%% { opacity: 0.3; } }
                        .pulse-dot { animation: pulse-dot 2s cubic-bezier(0.4, 0, 0.6, 1) infinite; }
                        @keyframes shimmer { 0%% { background-position: -1000px 0; } 100%% { background-position: 1000px 0; } }
                        .skeleton { animation: shimmer 2s infinite; background: linear-gradient(to right, #1e293b 4%%, #334155 25%%, #1e293b 36%%); background-size: 1000px 100%%; }
                    </style>
                </head>
                <body class="bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 text-gray-100 min-h-screen">
                
                    <header class="bg-gradient-to-r from-indigo-600 via-purple-600 to-pink-600 shadow-2xl">
                        <div class="max-w-7xl mx-auto px-6 py-8">
                            <div class="flex items-center justify-between flex-wrap gap-4">
                                <div>
                                    <h1 class="text-4xl font-bold text-white mb-2">CrudX Performance Analytics</h1>
                                    <p class="text-indigo-100">Real-time monitoring and insights</p>
                                    <div class="mt-3 flex items-center gap-4 text-sm text-indigo-200">
                                        <span>Framework v1.0.0</span>
                                        <span>•</span>
                                        <span>Built by <a href="https://www.linkedin.com/in/sachin-nimbal/" target="_blank" class="underline hover:text-white transition-colors">Sachin Nimbal</a></span>
                                    </div>
                                </div>
                                <div class="flex flex-col items-end gap-3">
                                    <div class="flex items-center gap-2 bg-green-500/20 px-4 py-2 rounded-full border border-green-400/30">
                                        <div class="w-2 h-2 bg-green-400 rounded-full pulse-dot"></div>
                                        <span class="text-green-300 text-sm font-semibold">LIVE</span>
                                    </div>
                                    <button onclick="toggleInfo()" class="text-indigo-200 hover:text-white text-sm underline transition-colors">
                                        About CrudX Framework
                                    </button>
                                </div>
                            </div>
                        </div>
                    </header>
                
                    <!-- Framework Info Modal -->
                    <div id="infoModal" class="hidden fixed inset-0 bg-black/70 z-50 flex items-center justify-center p-4">
                        <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl border border-slate-700 max-w-3xl w-full max-h-[90vh] overflow-y-auto">
                            <div class="p-8">
                                <div class="flex items-start justify-between mb-6">
                                    <div>
                                        <h2 class="text-3xl font-bold text-white mb-2">CrudX Framework</h2>
                                        <p class="text-slate-400">Zero-Boilerplate CRUD Operations for Spring Boot</p>
                                    </div>
                                    <button onclick="toggleInfo()" class="text-slate-400 hover:text-white transition-colors">
                                        <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                                        </svg>
                                    </button>
                                </div>
                
                                <div class="space-y-6">
                                    <!-- Version Info -->
                                    <div class="bg-slate-800/50 rounded-xl p-4 border border-slate-700">
                                        <div class="grid grid-cols-2 gap-4 text-sm">
                                            <div>
                                                <span class="text-slate-400">Version:</span>
                                                <span class="text-white font-semibold ml-2">1.0.0</span>
                                            </div>
                                            <div>
                                                <span class="text-slate-400">Since:</span>
                                                <span class="text-white font-semibold ml-2">2025</span>
                                            </div>
                                        </div>
                                    </div>
                
                                    <!-- Author Info -->
                                    <div>
                                        <h3 class="text-xl font-bold text-white mb-3">Author</h3>
                                        <div class="bg-slate-800/50 rounded-xl p-4 border border-slate-700">
                                            <div class="flex items-center gap-4 mb-3">
                                                <div class="w-16 h-16 bg-gradient-to-br from-indigo-600 to-purple-600 rounded-full flex items-center justify-center text-white text-2xl font-bold">
                                                    SN
                                                </div>
                                                <div>
                                                    <p class="text-white font-bold text-lg">Sachin Nimbal</p>
                                                    <p class="text-slate-400 text-sm">Full Stack Developer</p>
                                                </div>
                                            </div>
                                            <div class="space-y-2 text-sm">
                                                <div class="flex items-center gap-2">
                                                    <svg class="w-4 h-4 text-slate-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                                                    </svg>
                                                    <a href="mailto:sachinnimbal9@gmail.com" class="text-indigo-400 hover:text-indigo-300 transition-colors">sachinnimbal9@gmail.com</a>
                                                </div>
                                                <div class="flex items-center gap-2">
                                                    <svg class="w-4 h-4 text-slate-400" fill="currentColor" viewBox="0 0 24 24">
                                                        <path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433c-1.144 0-2.063-.926-2.063-2.065 0-1.138.92-2.063 2.063-2.063 1.14 0 2.064.925 2.064 2.063 0 1.139-.925 2.065-2.064 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z"/>
                                                    </svg>
                                                    <a href="https://www.linkedin.com/in/sachin-nimbal/" target="_blank" class="text-indigo-400 hover:text-indigo-300 transition-colors">linkedin.com/in/sachin-nimbal</a>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                
                                    <!-- Features -->
                                    <div>
                                        <h3 class="text-xl font-bold text-white mb-3">Key Features</h3>
                                        <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
                                            <div class="bg-slate-800/50 rounded-lg p-3 border border-slate-700">
                                                <div class="flex items-center gap-2 mb-1">
                                                    <svg class="w-5 h-5 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                                                    </svg>
                                                    <span class="text-white font-semibold">Zero Boilerplate</span>
                                                </div>
                                                <p class="text-slate-400 text-sm">Auto-generated services & controllers</p>
                                            </div>
                                            <div class="bg-slate-800/50 rounded-lg p-3 border border-slate-700">
                                                <div class="flex items-center gap-2 mb-1">
                                                    <svg class="w-5 h-5 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                                                    </svg>
                                                    <span class="text-white font-semibold">Multi-Database</span>
                                                </div>
                                                <p class="text-slate-400 text-sm">MySQL, PostgreSQL, MongoDB support</p>
                                            </div>
                                            <div class="bg-slate-800/50 rounded-lg p-3 border border-slate-700">
                                                <div class="flex items-center gap-2 mb-1">
                                                    <svg class="w-5 h-5 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                                                    </svg>
                                                    <span class="text-white font-semibold">Performance Tracking</span>
                                                </div>
                                                <p class="text-slate-400 text-sm">Built-in analytics dashboard</p>
                                            </div>
                                            <div class="bg-slate-800/50 rounded-lg p-3 border border-slate-700">
                                                <div class="flex items-center gap-2 mb-1">
                                                    <svg class="w-5 h-5 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                                                    </svg>
                                                    <span class="text-white font-semibold">Batch Operations</span>
                                                </div>
                                                <p class="text-slate-400 text-sm">Optimized bulk create/delete</p>
                                            </div>
                                        </div>
                                    </div>
                
                                    <!-- Quick Reference -->
                                    <div>
                                        <h3 class="text-xl font-bold text-white mb-3">Quick Reference</h3>
                                        <div class="bg-slate-800/50 rounded-xl p-4 border border-slate-700 space-y-3">
                                            <div>
                                                <p class="text-slate-400 text-sm mb-1">Documentation</p>
                                                <a href="#" class="text-indigo-400 hover:text-indigo-300 transition-colors text-sm">Coming Soon - Full Documentation</a>
                                            </div>
                                            <div>
                                                <p class="text-slate-400 text-sm mb-1">GitHub Repository</p>
                                                <a href="#" class="text-indigo-400 hover:text-indigo-300 transition-colors text-sm">Coming Soon - Source Code</a>
                                            </div>
                                            <div>
                                                <p class="text-slate-400 text-sm mb-1">Examples & Tutorials</p>
                                                <a href="#" class="text-indigo-400 hover:text-indigo-300 transition-colors text-sm">Coming Soon - Sample Projects</a>
                                            </div>
                                            <div>
                                                <p class="text-slate-400 text-sm mb-1">API Reference</p>
                                                <a href="#" class="text-indigo-400 hover:text-indigo-300 transition-colors text-sm">Coming Soon - Complete API Docs</a>
                                            </div>
                                        </div>
                                    </div>
                
                                    <!-- Memory Info -->
                                    <div class="bg-blue-500/10 border border-blue-500/30 rounded-xl p-4">
                                        <div class="flex items-start gap-3">
                                            <svg class="w-5 h-5 text-blue-400 mt-0.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                                            </svg>
                                            <div class="text-sm text-blue-300">
                                                <strong>About Memory Metrics:</strong> Memory values show thread-allocated memory during request processing, including JVM overhead, framework operations, and temporary objects. First requests typically show higher values due to JVM warmup. This is normal JVM behavior.
                                            </div>
                                        </div>
                                    </div>
                                </div>
                
                                <div class="mt-8 pt-6 border-t border-slate-700">
                                    <button onclick="toggleInfo()" class="w-full px-6 py-3 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 rounded-lg font-semibold shadow-lg transition-all">
                                        Close
                                    </button>
                                </div>
                            </div>
                        </div>
                    </div>
                
                    <main class="max-w-7xl mx-auto px-6 py-8">
                        <div class="flex flex-wrap items-center gap-4 mb-8">
                            <button onclick="loadData()" class="px-6 py-3 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 rounded-lg font-semibold shadow-lg transform hover:scale-105 transition-all">
                                <span class="flex items-center gap-2">
                                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                                    </svg>
                                    Refresh
                                </span>
                            </button>
                            <button onclick="toggleAutoRefresh()" class="px-6 py-3 bg-slate-700 hover:bg-slate-600 rounded-lg font-semibold shadow-lg transition-all">
                                <span id="autoRefreshText">⏸ Pause Auto-Refresh</span>
                            </button>
                            <button onclick="clearMetrics()" class="px-6 py-3 bg-gradient-to-r from-red-600 to-red-700 hover:from-red-700 hover:to-red-800 rounded-lg font-semibold shadow-lg transition-all">
                                <span class="flex items-center gap-2">
                                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                                    </svg>
                                    Clear Metrics
                                </span>
                            </button>
                            <div class="ml-auto text-sm text-slate-400">
                                Last updated: <span id="lastUpdate" class="text-slate-300 font-semibold">-</span>
                            </div>
                        </div>
                
                        <div id="error" class="hidden mb-8 bg-red-500/10 border border-red-500/50 rounded-xl p-4 flex items-center gap-3">
                            <svg class="w-6 h-6 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                            </svg>
                            <span id="errorText" class="text-red-300"></span>
                        </div>
                
                        <div id="loading" class="text-center py-20">
                            <div class="inline-block w-16 h-16 border-4 border-indigo-500 border-t-transparent rounded-full animate-spin"></div>
                            <p class="mt-4 text-xl text-slate-400">Loading analytics...</p>
                        </div>
                
                        <div id="content" class="hidden space-y-8">
                            <!-- KPI Cards -->
                            <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
                                    <div class="flex items-center justify-between mb-4">
                                        <div class="p-3 bg-blue-500/20 rounded-xl">
                                            <svg class="w-6 h-6 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                                            </svg>
                                        </div>
                                    </div>
                                    <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Total Requests</p>
                                    <p class="text-4xl font-bold text-white" id="totalRequests">0</p>
                                </div>
                
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
                                    <div class="flex items-center justify-between mb-4">
                                        <div class="p-3 bg-green-500/20 rounded-xl">
                                            <svg class="w-6 h-6 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                                            </svg>
                                        </div>
                                    </div>
                                    <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Success Rate</p>
                                    <p class="text-4xl font-bold text-white"><span id="successRate">0</span>%%</p>
                                    <div class="mt-3 bg-slate-700 rounded-full h-2 overflow-hidden">
                                        <div id="successBar" class="bg-gradient-to-r from-green-500 to-emerald-500 h-full transition-all duration-500" style="width: 0%%"></div>
                                    </div>
                                </div>
                
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
                                    <div class="flex items-center justify-between mb-4">
                                        <div class="p-3 bg-purple-500/20 rounded-xl">
                                            <svg class="w-6 h-6 text-purple-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                                            </svg>
                                        </div>
                                    </div>
                                    <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Avg Response</p>
                                    <p class="text-4xl font-bold text-white"><span id="avgTime">0</span><span class="text-2xl text-slate-400">ms</span></p>
                                </div>
                
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
                                    <div class="flex items-center justify-between mb-4">
                                        <div class="p-3 bg-orange-500/20 rounded-xl">
                                            <svg class="w-6 h-6 text-orange-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
                                            </svg>
                                        </div>
                                    </div>
                                    <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Avg Memory</p>
                                    <p class="text-4xl font-bold text-white" id="avgMemory">0 KB</p>
                                    <p class="text-xs text-slate-500 mt-2">Thread allocation</p>
                                </div>
                            </div>
                
                            <!-- Charts Row 1 -->
                            <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                    <h3 class="text-xl font-bold mb-2">Response Time Distribution</h3>
                                    <p class="text-sm text-slate-400 mb-6">Performance categories</p>
                                    <div class="relative h-64">
                                        <canvas id="responseTimeChart"></canvas>
                                    </div>
                                </div>
                
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                    <h3 class="text-xl font-bold mb-2">Request Status</h3>
                                    <p class="text-sm text-slate-400 mb-6">Success vs Failed</p>
                                    <div class="relative h-64">
                                        <canvas id="statusChart"></canvas>
                                    </div>
                                </div>
                            </div>
                
                            <!-- Charts Row 2 -->
                            <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                    <h3 class="text-xl font-bold mb-2">HTTP Methods Distribution</h3>
                                    <p class="text-sm text-slate-400 mb-6">Request types breakdown</p>
                                    <div class="relative h-64">
                                        <canvas id="methodsChart"></canvas>
                                    </div>
                                </div>
                
                                <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                    <h3 class="text-xl font-bold mb-2">Top 10 Slowest Endpoints</h3>
                                    <p class="text-sm text-slate-400 mb-6">Average response time</p>
                                    <div class="relative h-64">
                                        <canvas id="slowestChart"></canvas>
                                    </div>
                                </div>
                            </div>
                
                            <!-- Memory & Timeline Charts -->
                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                <h3 class="text-xl font-bold mb-2">Memory Usage by Endpoint</h3>
                                <p class="text-sm text-slate-400 mb-6">Top 10 memory-intensive endpoints</p>
                                <div class="relative h-80">
                                    <canvas id="memoryChart"></canvas>
                                </div>
                            </div>
                
                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                <h3 class="text-xl font-bold mb-2">Response Time Timeline</h3>
                                <p class="text-sm text-slate-400 mb-6">Last 20 requests</p>
                                <div class="relative h-80">
                                    <canvas id="timelineChart"></canvas>
                                </div>
                            </div>
                
                            <!-- Endpoint Performance Table -->
                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
                                <h3 class="text-xl font-bold mb-2">Endpoint Performance</h3>
                                <p class="text-sm text-slate-400 mb-6">Detailed metrics per endpoint</p>
                                <div class="overflow-x-auto">
                                    <table class="w-full" id="endpointTable">
                                        <thead>
                                            <tr class="border-b border-slate-700">
                                                <th class="text-left py-3 px-4 text-sm font-semibold text-slate-300">Method</th>
                                                <th class="text-left py-3 px-4 text-sm font-semibold text-slate-300">Endpoint</th>
                                                <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Avg Memory</th>
                                                <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Success Rate</th>
                                            </tr>
                                        </thead>
                                        <tbody id="endpointList"></tbody>
                                    </table>
                                </div>
                            </div>
                        </div>
                    </main>
                
                    <!-- Footer -->
                    <footer class="max-w-7xl mx-auto px-6 py-8 mt-12 border-t border-slate-700">
                        <div class="flex flex-col md:flex-row items-center justify-between gap-4 text-sm text-slate-400">
                            <div class="text-center md:text-left">
                                <p>&copy; 2025 CrudX Framework. Built with ❤️ by <a href="https://www.linkedin.com/in/sachin-nimbal/" target="_blank" class="text-indigo-400 hover:text-indigo-300 transition-colors">Sachin Nimbal</a></p>
                            </div>
                            <div class="flex items-center gap-6">
                                <a href="mailto:sachinnimbal9@gmail.com" class="hover:text-white transition-colors">Contact</a>
                                <span>•</span>
                                <button onclick="toggleInfo()" class="hover:text-white transition-colors">About</button>
                                <span>•</span>
                                <span>v1.0.0</span>
                            </div>
                        </div>
                    </footer>
                
                    <script>
                        const API_BASE = '%s';
                        let autoRefresh = true;
                        let refreshInterval;
                        let charts = {};
                
                        const chartDefaults = {
                            responsive: true,
                            maintainAspectRatio: false,
                            plugins: { legend: { labels: { color: '#cbd5e1', font: { size: 12 } } } }
                        };
                
                        function toggleInfo() {
                            const modal = document.getElementById('infoModal');
                            modal.classList.toggle('hidden');
                        }
                
                        function formatMemory(kb) {
                            if (!kb && kb !== 0) return 'N/A';
                            if (kb < 1024) return kb.toFixed(0) + ' KB';
                            return (kb / 1024).toFixed(2) + ' MB';
                        }
                
                        function getMemoryBadge(kb) {
                            if (!kb) return 'bg-slate-600';
                            if (kb < 1024) return 'bg-green-500/20 text-green-400 border-green-500/30';
                            if (kb < 5120) return 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30';
                            return 'bg-red-500/20 text-red-400 border-red-500/30';
                        }
                
                        function getMethodBadge(method) {
                            const badges = {
                                'GET': 'bg-green-500/20 text-green-400 border-green-500/30',
                                'POST': 'bg-blue-500/20 text-blue-400 border-blue-500/30',
                                'PUT': 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
                                'PATCH': 'bg-purple-500/20 text-purple-400 border-purple-500/30',
                                'DELETE': 'bg-red-500/20 text-red-400 border-red-500/30'
                            };
                            return badges[method] || 'bg-slate-600';
                        }
                
                        async function loadData() {
                            try {
                                const [summaryRes, metricsRes] = await Promise.all([
                                    fetch(`${API_BASE}/summary`),
                                    fetch(`${API_BASE}/metrics`)
                                ]);
                
                                const summary = await summaryRes.json();
                                const metrics = await metricsRes.json();
                
                                if (summary.success && metrics.success) {
                                    updateKPIs(summary.data);
                                    updateCharts(summary.data, metrics.data);
                                    updateTable(summary.data);
                
                                    document.getElementById('loading').classList.add('hidden');
                                    document.getElementById('content').classList.remove('hidden');
                                    document.getElementById('error').classList.add('hidden');
                                    document.getElementById('lastUpdate').textContent = new Date().toLocaleTimeString();
                                }
                            } catch (error) {
                                showError('Connection error: ' + error.message);
                            }
                        }
                
                        function updateKPIs(data) {
                            document.getElementById('totalRequests').textContent = (data.totalRequests || 0).toLocaleString();
                            document.getElementById('successRate').textContent = (data.successRate || 0).toFixed(1);
                            document.getElementById('successBar').style.width = (data.successRate || 0) + '%%';
                            document.getElementById('avgTime').textContent = (data.avgExecutionTimeMs || 0).toFixed(0);
                            document.getElementById('avgMemory').textContent = formatMemory(data.avgMemoryKb || 0);
                        }
                
                        function updateCharts(summary, metrics) {
                            createResponseTimeChart(metrics);
                            createStatusChart(summary);
                            createMethodsChart(metrics);
                            createSlowestChart(summary);
                            createMemoryChart(summary);
                            createTimelineChart(metrics);
                        }
                
                        function createResponseTimeChart(metrics) {
                            const ctx = document.getElementById('responseTimeChart').getContext('2d');
                            if (charts.responseTime) charts.responseTime.destroy();
                
                            const fast = metrics.filter(m => m.executionTimeMs < 50).length;
                            const medium = metrics.filter(m => m.executionTimeMs >= 50 && m.executionTimeMs < 200).length;
                            const slow = metrics.filter(m => m.executionTimeMs >= 200).length;
                
                            charts.responseTime = new Chart(ctx, {
                                type: 'pie',
                                data: {
                                    labels: ['Fast (<50ms)', 'Medium (50-200ms)', 'Slow (>200ms)'],
                                    datasets: [{
                                        data: [fast, medium, slow],
                                        backgroundColor: ['#10b981', '#f59e0b', '#ef4444'],
                                        borderWidth: 2,
                                        borderColor: '#1e293b'
                                    }]
                                },
                                options: chartDefaults
                            });
                        }
                
                        function createStatusChart(summary) {
                            const ctx = document.getElementById('statusChart').getContext('2d');
                            if (charts.status) charts.status.destroy();
                
                            charts.status = new Chart(ctx, {
                                type: 'doughnut',
                                data: {
                                    labels: ['Successful', 'Failed'],
                                    datasets: [{
                                        data: [summary.successfulRequests || 0, summary.failedRequests || 0],
                                        backgroundColor: ['#10b981', '#ef4444'],
                                        borderWidth: 2,
                                        borderColor: '#1e293b'
                                    }]
                                },
                                options: chartDefaults
                            });
                        }
                
                        function createMethodsChart(metrics) {
                            const ctx = document.getElementById('methodsChart').getContext('2d');
                            if (charts.methods) charts.methods.destroy();
                
                            const methodCounts = {};
                            metrics.forEach(m => {
                                methodCounts[m.method] = (methodCounts[m.method] || 0) + 1;
                            });
                
                            const colors = {
                                'GET': '#10b981',
                                'POST': '#3b82f6',
                                'PUT': '#f59e0b',
                                'PATCH': '#a855f7',
                                'DELETE': '#ef4444'
                            };
                
                            charts.methods = new Chart(ctx, {
                                type: 'doughnut',
                                data: {
                                    labels: Object.keys(methodCounts),
                                    datasets: [{
                                        data: Object.values(methodCounts),
                                        backgroundColor: Object.keys(methodCounts).map(m => colors[m] || '#64748b'),
                                        borderWidth: 2,
                                        borderColor: '#1e293b'
                                    }]
                                },
                                options: chartDefaults
                            });
                        }
                
                        function createSlowestChart(summary) {
                            const ctx = document.getElementById('slowestChart').getContext('2d');
                            if (charts.slowest) charts.slowest.destroy();
                
                            const sorted = Object.entries(summary.endpointStats || {})
                                .sort((a, b) => b[1].avgExecutionTimeMs - a[1].avgExecutionTimeMs)
                                .slice(0, 10);
                
                            charts.slowest = new Chart(ctx, {
                                type: 'bar',
                                data: {
                                    labels: sorted.map(([key]) => key.split(' ')[1] || key),
                                    datasets: [{
                                        label: 'Avg Time (ms)',
                                        data: sorted.map(([_, stats]) => stats.avgExecutionTimeMs),
                                        backgroundColor: sorted.map(([_, stats]) => 
                                            stats.avgExecutionTimeMs < 50 ? 'rgba(16, 185, 129, 0.7)' :
                                            stats.avgExecutionTimeMs < 200 ? 'rgba(245, 158, 11, 0.7)' :
                                            'rgba(239, 68, 68, 0.7)'
                                        ),
                                        borderColor: 'rgba(99, 102, 241, 1)',
                                        borderWidth: 2
                                    }]
                                },
                                options: {
                                    ...chartDefaults,
                                    indexAxis: 'y',
                                    scales: {
                                        x: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } },
                                        y: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } }
                                    }
                                }
                            });
                        }
                
                        function createMemoryChart(summary) {
                            const ctx = document.getElementById('memoryChart').getContext('2d');
                            if (charts.memory) charts.memory.destroy();
                
                            const sorted = Object.entries(summary.endpointStats || {})
                                .filter(([_, stats]) => stats.avgMemoryKb)
                                .sort((a, b) => b[1].avgMemoryKb - a[1].avgMemoryKb)
                                .slice(0, 10);
                
                            charts.memory = new Chart(ctx, {
                                type: 'bar',
                                data: {
                                    labels: sorted.map(([key]) => key.split(' ')[1] || key),
                                    datasets: [{
                                        label: 'Avg Memory (KB)',
                                        data: sorted.map(([_, stats]) => stats.avgMemoryKb),
                                        backgroundColor: 'rgba(249, 115, 22, 0.7)',
                                        borderColor: 'rgba(249, 115, 22, 1)',
                                        borderWidth: 2
                                    }]
                                },
                                options: {
                                    ...chartDefaults,
                                    scales: {
                                        y: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } },
                                        x: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } }
                                    }
                                }
                            });
                        }
                
                        function createTimelineChart(metrics) {
                            const ctx = document.getElementById('timelineChart').getContext('2d');
                            if (charts.timeline) charts.timeline.destroy();
                
                            const last20 = metrics.slice(-20);
                
                            charts.timeline = new Chart(ctx, {
                                type: 'line',
                                data: {
                                    labels: last20.map((_, i) => `#${i + 1}`),
                                    datasets: [{
                                        label: 'Response Time (ms)',
                                        data: last20.map(m => m.executionTimeMs),
                                        borderColor: 'rgba(99, 102, 241, 1)',
                                        backgroundColor: 'rgba(99, 102, 241, 0.1)',
                                        tension: 0.4,
                                        fill: true
                                    }]
                                },
                                options: {
                                    ...chartDefaults,
                                    scales: {
                                        y: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } },
                                        x: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } }
                                    }
                                }
                            });
                        }
                
                        function updateTable(data) {
                            const tbody = document.getElementById('endpointList');
                            tbody.innerHTML = '';
                
                            if (!data.endpointStats || Object.keys(data.endpointStats).length === 0) {
                                tbody.innerHTML = '<tr><td colspan="7" class="py-12 text-center text-slate-400">No data yet</td></tr>';
                                return;
                            }
                
                            Object.entries(data.endpointStats).forEach(([key, stats]) => {
                                const successRate = stats.totalCalls > 0 ? 
                                    ((stats.totalCalls - stats.failedCalls) / stats.totalCalls * 100).toFixed(1) : 0;
                
                                const row = document.createElement('tr');
                                row.className = 'border-b border-slate-700/50 hover:bg-slate-700/30 transition-colors';
                                row.innerHTML = `
                                    <td class="py-3 px-4">
                                        <span class="px-3 py-1 rounded-lg text-xs font-bold border ${getMethodBadge(stats.method)}">
                                            ${stats.method}
                                        </span>
                                    </td>
                                    <td class="py-3 px-4">
                                        <div class="font-medium text-slate-200">${stats.endpoint}</div>
                                        ${stats.entityName ? `<div class="text-xs text-slate-400 mt-1">${stats.entityName}</div>` : ''}
                                    </td>
                                    <td class="py-3 px-4 text-center font-semibold">${stats.totalCalls}</td>
                                    <td class="py-3 px-4 text-center">
                                        <span class="px-2 py-1 rounded-lg text-sm ${
                                            stats.avgExecutionTimeMs < 50 ? 'bg-green-500/20 text-green-400' :
                                            stats.avgExecutionTimeMs < 200 ? 'bg-yellow-500/20 text-yellow-400' :
                                            'bg-red-500/20 text-red-400'
                                        }">
                                            ${stats.avgExecutionTimeMs.toFixed(0)}ms
                                        </span>
                                    </td>
                                    <td class="py-3 px-4 text-center text-slate-300">
                                        <div class="text-xs">${stats.minExecutionTimeMs || 0}ms / ${stats.maxExecutionTimeMs}ms</div>
                                    </td>
                                    <td class="py-3 px-4 text-center">
                                        ${stats.avgMemoryKb ? 
                                            `<span class="px-2 py-1 rounded-lg text-sm border ${getMemoryBadge(stats.avgMemoryKb)}">${formatMemory(stats.avgMemoryKb)}</span>` :
                                            '<span class="text-slate-500">N/A</span>'
                                        }
                                    </td>
                                    <td class="py-3 px-4 text-center">
                                        <div class="flex items-center justify-center gap-2">
                                            <span class="text-sm font-semibold ${successRate >= 95 ? 'text-green-400' : successRate >= 80 ? 'text-yellow-400' : 'text-red-400'}">
                                                ${successRate}%%
                                            </span>
                                            ${stats.failedCalls > 0 ? 
                                                `<span class="px-2 py-0.5 bg-red-500/20 text-red-400 rounded text-xs">${stats.failedCalls} failed</span>` :
                                                '<span class="text-green-400 text-xs">✓</span>'
                                            }
                                        </div>
                                    </td>
                                `;
                                tbody.appendChild(row);
                            });
                        }
                
                        async function clearMetrics() {
                            if (!confirm('Clear all performance metrics? This cannot be undone.')) return;
                            try {
                                const response = await fetch(`${API_BASE}/metrics`, { method: 'DELETE' });
                                const result = await response.json();
                                if (result.success) {
                                    Object.values(charts).forEach(chart => chart && chart.destroy());
                                    charts = {};
                                    loadData();
                                } else {
                                    showError('Failed to clear metrics');
                                }
                            } catch (error) {
                                showError('Error: ' + error.message);
                            }
                        }
                
                        function toggleAutoRefresh() {
                            autoRefresh = !autoRefresh;
                            const btn = document.getElementById('autoRefreshText');
                            if (autoRefresh) {
                                btn.textContent = '⏸ Pause Auto-Refresh';
                                startAutoRefresh();
                            } else {
                                btn.textContent = '▶ Resume Auto-Refresh';
                                stopAutoRefresh();
                            }
                        }
                
                        function startAutoRefresh() {
                            if (refreshInterval) clearInterval(refreshInterval);
                            refreshInterval = setInterval(loadData, 5000);
                        }
                
                        function stopAutoRefresh() {
                            if (refreshInterval) {
                                clearInterval(refreshInterval);
                                refreshInterval = null;
                            }
                        }
                
                        function showError(message) {
                            document.getElementById('errorText').textContent = message;
                            document.getElementById('error').classList.remove('hidden');
                            document.getElementById('loading').classList.add('hidden');
                        }
                
                        loadData();
                        startAutoRefresh();
                    </script>
                </body>
                </html>
                """.formatted(apiBase);
    }

}
//    private String getDashboardHtml() {
//        String apiBase = properties.getDashboardPath();
//
//        return """
//            <!DOCTYPE html>
//            <html lang="en">
//            <head>
//                <meta charset="UTF-8">
//                <meta name="viewport" content="width=device-width, initial-scale=1.0">
//                <title>CrudX Performance Analytics</title>
//                <script src="https://cdn.tailwindcss.com"></script>
//                <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0"></script>
//                <style>
//                    @keyframes pulse-dot { 0%%, 100%% { opacity: 1; } 50%% { opacity: 0.3; } }
//                    .pulse-dot { animation: pulse-dot 2s cubic-bezier(0.4, 0, 0.6, 1) infinite; }
//                    @keyframes shimmer { 0%% { background-position: -1000px 0; } 100%% { background-position: 1000px 0; } }
//                    .skeleton { animation: shimmer 2s infinite; background: linear-gradient(to right, #1e293b 4%%, #334155 25%%, #1e293b 36%%); background-size: 1000px 100%%; }
//                </style>
//            </head>
//            <body class="bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 text-gray-100 min-h-screen">
//
//                <header class="bg-gradient-to-r from-indigo-600 via-purple-600 to-pink-600 shadow-2xl">
//                    <div class="max-w-7xl mx-auto px-6 py-8">
//                        <div class="flex items-center justify-between">
//                            <div>
//                                <h1 class="text-4xl font-bold text-white mb-2">CrudX Performance Analytics</h1>
//                                <p class="text-indigo-100">Real-time monitoring and insights</p>
//                            </div>
//                            <div class="flex items-center gap-2 bg-green-500/20 px-4 py-2 rounded-full border border-green-400/30">
//                                <div class="w-2 h-2 bg-green-400 rounded-full pulse-dot"></div>
//                                <span class="text-green-300 text-sm font-semibold">LIVE</span>
//                            </div>
//                        </div>
//                    </div>
//                </header>
//
//                <main class="max-w-7xl mx-auto px-6 py-8">
//                    <div class="flex flex-wrap items-center gap-4 mb-8">
//                        <button onclick="loadData()" class="px-6 py-3 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 rounded-lg font-semibold shadow-lg transform hover:scale-105 transition-all">
//                            <span class="flex items-center gap-2">
//                                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
//                                </svg>
//                                Refresh
//                            </span>
//                        </button>
//                        <button onclick="toggleAutoRefresh()" class="px-6 py-3 bg-slate-700 hover:bg-slate-600 rounded-lg font-semibold shadow-lg transition-all">
//                            <span id="autoRefreshText">⏸ Pause Auto-Refresh</span>
//                        </button>
//                        <button onclick="clearMetrics()" class="px-6 py-3 bg-gradient-to-r from-red-600 to-red-700 hover:from-red-700 hover:to-red-800 rounded-lg font-semibold shadow-lg transition-all">
//                            <span class="flex items-center gap-2">
//                                <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
//                                </svg>
//                                Clear Metrics
//                            </span>
//                        </button>
//                        <div class="ml-auto text-sm text-slate-400">
//                            Last updated: <span id="lastUpdate" class="text-slate-300 font-semibold">-</span>
//                        </div>
//                    </div>
//
//                    <div id="error" class="hidden mb-8 bg-red-500/10 border border-red-500/50 rounded-xl p-4 flex items-center gap-3">
//                        <svg class="w-6 h-6 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
//                        </svg>
//                        <span id="errorText" class="text-red-300"></span>
//                    </div>
//
//                    <div id="loading" class="text-center py-20">
//                        <div class="inline-block w-16 h-16 border-4 border-indigo-500 border-t-transparent rounded-full animate-spin"></div>
//                        <p class="mt-4 text-xl text-slate-400">Loading analytics...</p>
//                    </div>
//
//                    <div id="content" class="hidden space-y-8">
//                        <!-- Info Banner -->
//                        <div class="bg-blue-500/10 border border-blue-500/30 rounded-xl p-4 flex items-start gap-3">
//                            <svg class="w-5 h-5 text-blue-400 mt-0.5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
//                            </svg>
//                            <div class="text-sm text-blue-300">
//                                <strong>About Memory Metrics:</strong> Memory values show thread-allocated memory during request processing, including JVM overhead, framework operations, and temporary objects. First requests typically show higher values due to JVM warmup and class loading. This is normal JVM behavior.
//                            </div>
//                        </div>
//
//                        <!-- KPI Cards -->
//                        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
//                                <div class="flex items-center justify-between mb-4">
//                                    <div class="p-3 bg-blue-500/20 rounded-xl">
//                                        <svg class="w-6 h-6 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
//                                        </svg>
//                                    </div>
//                                </div>
//                                <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Total Requests</p>
//                                <p class="text-4xl font-bold text-white" id="totalRequests">0</p>
//                            </div>
//
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
//                                <div class="flex items-center justify-between mb-4">
//                                    <div class="p-3 bg-green-500/20 rounded-xl">
//                                        <svg class="w-6 h-6 text-green-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
//                                        </svg>
//                                    </div>
//                                </div>
//                                <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Success Rate</p>
//                                <p class="text-4xl font-bold text-white"><span id="successRate">0</span>%%</p>
//                                <div class="mt-3 bg-slate-700 rounded-full h-2 overflow-hidden">
//                                    <div id="successBar" class="bg-gradient-to-r from-green-500 to-emerald-500 h-full transition-all duration-500" style="width: 0%%"></div>
//                                </div>
//                            </div>
//
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
//                                <div class="flex items-center justify-between mb-4">
//                                    <div class="p-3 bg-purple-500/20 rounded-xl">
//                                        <svg class="w-6 h-6 text-purple-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
//                                        </svg>
//                                    </div>
//                                </div>
//                                <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Avg Response</p>
//                                <p class="text-4xl font-bold text-white"><span id="avgTime">0</span><span class="text-2xl text-slate-400">ms</span></p>
//                            </div>
//
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl hover:shadow-2xl transform hover:-translate-y-1 transition-all">
//                                <div class="flex items-center justify-between mb-4">
//                                    <div class="p-3 bg-orange-500/20 rounded-xl">
//                                        <svg class="w-6 h-6 text-orange-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
//                                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z" />
//                                        </svg>
//                                    </div>
//                                </div>
//                                <p class="text-sm text-slate-400 uppercase tracking-wider font-semibold mb-2">Avg Memory</p>
//                                <p class="text-4xl font-bold text-white" id="avgMemory">0 KB</p>
//                                <p class="text-xs text-slate-500 mt-2">Thread allocation</p>
//                            </div>
//                        </div>
//
//                        <!-- Charts Row 1 -->
//                        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                                <h3 class="text-xl font-bold mb-2">Response Time Distribution</h3>
//                                <p class="text-sm text-slate-400 mb-6">Performance categories</p>
//                                <div class="relative h-64">
//                                    <canvas id="responseTimeChart"></canvas>
//                                </div>
//                            </div>
//
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                                <h3 class="text-xl font-bold mb-2">Request Status</h3>
//                                <p class="text-sm text-slate-400 mb-6">Success vs Failed</p>
//                                <div class="relative h-64">
//                                    <canvas id="statusChart"></canvas>
//                                </div>
//                            </div>
//                        </div>
//
//                        <!-- Charts Row 2 -->
//                        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                                <h3 class="text-xl font-bold mb-2">HTTP Methods Distribution</h3>
//                                <p class="text-sm text-slate-400 mb-6">Request types breakdown</p>
//                                <div class="relative h-64">
//                                    <canvas id="methodsChart"></canvas>
//                                </div>
//                            </div>
//
//                            <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                                <h3 class="text-xl font-bold mb-2">Top 10 Slowest Endpoints</h3>
//                                <p class="text-sm text-slate-400 mb-6">Average response time</p>
//                                <div class="relative h-64">
//                                    <canvas id="slowestChart"></canvas>
//                                </div>
//                            </div>
//                        </div>
//
//                        <!-- Memory & Timeline Charts -->
//                        <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                            <h3 class="text-xl font-bold mb-2">Memory Usage by Endpoint</h3>
//                            <p class="text-sm text-slate-400 mb-6">Top 10 memory-intensive endpoints</p>
//                            <div class="relative h-80">
//                                <canvas id="memoryChart"></canvas>
//                            </div>
//                        </div>
//
//                        <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                            <h3 class="text-xl font-bold mb-2">Response Time Timeline</h3>
//                            <p class="text-sm text-slate-400 mb-6">Last 20 requests</p>
//                            <div class="relative h-80">
//                                <canvas id="timelineChart"></canvas>
//                            </div>
//                        </div>
//
//                        <!-- Endpoint Performance Table -->
//                        <div class="bg-gradient-to-br from-slate-800 to-slate-900 rounded-2xl p-6 border border-slate-700 shadow-xl">
//                            <h3 class="text-xl font-bold mb-2">Endpoint Performance</h3>
//                            <p class="text-sm text-slate-400 mb-6">Detailed metrics per endpoint</p>
//                            <div class="overflow-x-auto">
//                                <table class="w-full" id="endpointTable">
//                                    <thead>
//                                        <tr class="border-b border-slate-700">
//                                            <th class="text-left py-3 px-4 text-sm font-semibold text-slate-300">Method</th>
//                                            <th class="text-left py-3 px-4 text-sm font-semibold text-slate-300">Endpoint</th>
//                                            <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Calls</th>
//                                            <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Avg Time</th>
//                                            <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Min/Max</th>
//                                            <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Avg Memory</th>
//                                            <th class="text-center py-3 px-4 text-sm font-semibold text-slate-300">Success Rate</th>
//                                        </tr>
//                                    </thead>
//                                    <tbody id="endpointList"></tbody>
//                                </table>
//                            </div>
//                        </div>
//                    </div>
//                </main>
//
//                <script>
//                    const API_BASE = '%s';
//                    let autoRefresh = true;
//                    let refreshInterval;
//                    let charts = {};
//
//                    const chartDefaults = {
//                        responsive: true,
//                        maintainAspectRatio: false,
//                        plugins: { legend: { labels: { color: '#cbd5e1', font: { size: 12 } } } }
//                    };
//
//                    function formatMemory(kb) {
//                        if (!kb && kb !== 0) return 'N/A';
//                        if (kb < 1024) return kb.toFixed(0) + ' KB';
//                        return (kb / 1024).toFixed(2) + ' MB';
//                    }
//
//                    function getMemoryBadge(kb) {
//                        if (!kb) return 'bg-slate-600';
//                        if (kb < 1024) return 'bg-green-500/20 text-green-400 border-green-500/30';
//                        if (kb < 5120) return 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30';
//                        return 'bg-red-500/20 text-red-400 border-red-500/30';
//                    }
//
//                    function getMethodBadge(method) {
//                        const badges = {
//                            'GET': 'bg-green-500/20 text-green-400 border-green-500/30',
//                            'POST': 'bg-blue-500/20 text-blue-400 border-blue-500/30',
//                            'PUT': 'bg-yellow-500/20 text-yellow-400 border-yellow-500/30',
//                            'PATCH': 'bg-purple-500/20 text-purple-400 border-purple-500/30',
//                            'DELETE': 'bg-red-500/20 text-red-400 border-red-500/30'
//                        };
//                        return badges[method] || 'bg-slate-600';
//                    }
//
//                    async function loadData() {
//                        try {
//                            const [summaryRes, metricsRes] = await Promise.all([
//                                fetch(`${API_BASE}/summary`),
//                                fetch(`${API_BASE}/metrics`)
//                            ]);
//
//                            const summary = await summaryRes.json();
//                            const metrics = await metricsRes.json();
//
//                            if (summary.success && metrics.success) {
//                                updateKPIs(summary.data);
//                                updateCharts(summary.data, metrics.data);
//                                updateTable(summary.data);
//
//                                document.getElementById('loading').classList.add('hidden');
//                                document.getElementById('content').classList.remove('hidden');
//                                document.getElementById('error').classList.add('hidden');
//                                document.getElementById('lastUpdate').textContent = new Date().toLocaleTimeString();
//                            }
//                        } catch (error) {
//                            showError('Connection error: ' + error.message);
//                        }
//                    }
//
//                    function updateKPIs(data) {
//                        document.getElementById('totalRequests').textContent = (data.totalRequests || 0).toLocaleString();
//                        document.getElementById('successRate').textContent = (data.successRate || 0).toFixed(1);
//                        document.getElementById('successBar').style.width = (data.successRate || 0) + '%%';
//                        document.getElementById('avgTime').textContent = (data.avgExecutionTimeMs || 0).toFixed(0);
//                        document.getElementById('avgMemory').textContent = formatMemory(data.avgMemoryKb || 0);
//                    }
//
//                    function updateCharts(summary, metrics) {
//                        createResponseTimeChart(metrics);
//                        createStatusChart(summary);
//                        createMethodsChart(metrics);
//                        createSlowestChart(summary);
//                        createMemoryChart(summary);
//                        createTimelineChart(metrics);
//                    }
//
//                    function createResponseTimeChart(metrics) {
//                        const ctx = document.getElementById('responseTimeChart').getContext('2d');
//                        if (charts.responseTime) charts.responseTime.destroy();
//
//                        const fast = metrics.filter(m => m.executionTimeMs < 50).length;
//                        const medium = metrics.filter(m => m.executionTimeMs >= 50 && m.executionTimeMs < 200).length;
//                        const slow = metrics.filter(m => m.executionTimeMs >= 200).length;
//
//                        charts.responseTime = new Chart(ctx, {
//                            type: 'pie',
//                            data: {
//                                labels: ['Fast (<50ms)', 'Medium (50-200ms)', 'Slow (>200ms)'],
//                                datasets: [{
//                                    data: [fast, medium, slow],
//                                    backgroundColor: ['#10b981', '#f59e0b', '#ef4444'],
//                                    borderWidth: 2,
//                                    borderColor: '#1e293b'
//                                }]
//                            },
//                            options: chartDefaults
//                        });
//                    }
//
//                    function createStatusChart(summary) {
//                        const ctx = document.getElementById('statusChart').getContext('2d');
//                        if (charts.status) charts.status.destroy();
//
//                        charts.status = new Chart(ctx, {
//                            type: 'doughnut',
//                            data: {
//                                labels: ['Successful', 'Failed'],
//                                datasets: [{
//                                    data: [summary.successfulRequests || 0, summary.failedRequests || 0],
//                                    backgroundColor: ['#10b981', '#ef4444'],
//                                    borderWidth: 2,
//                                    borderColor: '#1e293b'
//                                }]
//                            },
//                            options: chartDefaults
//                        });
//                    }
//
//                    function createMethodsChart(metrics) {
//                        const ctx = document.getElementById('methodsChart').getContext('2d');
//                        if (charts.methods) charts.methods.destroy();
//
//                        const methodCounts = {};
//                        metrics.forEach(m => {
//                            methodCounts[m.method] = (methodCounts[m.method] || 0) + 1;
//                        });
//
//                        const colors = {
//                            'GET': '#10b981',
//                            'POST': '#3b82f6',
//                            'PUT': '#f59e0b',
//                            'PATCH': '#a855f7',
//                            'DELETE': '#ef4444'
//                        };
//
//                        charts.methods = new Chart(ctx, {
//                            type: 'doughnut',
//                            data: {
//                                labels: Object.keys(methodCounts),
//                                datasets: [{
//                                    data: Object.values(methodCounts),
//                                    backgroundColor: Object.keys(methodCounts).map(m => colors[m] || '#64748b'),
//                                    borderWidth: 2,
//                                    borderColor: '#1e293b'
//                                }]
//                            },
//                            options: chartDefaults
//                        });
//                    }
//
//                    function createSlowestChart(summary) {
//                        const ctx = document.getElementById('slowestChart').getContext('2d');
//                        if (charts.slowest) charts.slowest.destroy();
//
//                        const sorted = Object.entries(summary.endpointStats || {})
//                            .sort((a, b) => b[1].avgExecutionTimeMs - a[1].avgExecutionTimeMs)
//                            .slice(0, 10);
//
//                        charts.slowest = new Chart(ctx, {
//                            type: 'bar',
//                            data: {
//                                labels: sorted.map(([key]) => key.split(' ')[1] || key),
//                                datasets: [{
//                                    label: 'Avg Time (ms)',
//                                    data: sorted.map(([_, stats]) => stats.avgExecutionTimeMs),
//                                    backgroundColor: sorted.map(([_, stats]) =>
//                                        stats.avgExecutionTimeMs < 50 ? 'rgba(16, 185, 129, 0.7)' :
//                                        stats.avgExecutionTimeMs < 200 ? 'rgba(245, 158, 11, 0.7)' :
//                                        'rgba(239, 68, 68, 0.7)'
//                                    ),
//                                    borderColor: 'rgba(99, 102, 241, 1)',
//                                    borderWidth: 2
//                                }]
//                            },
//                            options: {
//                                ...chartDefaults,
//                                indexAxis: 'y',
//                                scales: {
//                                    x: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } },
//                                    y: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } }
//                                }
//                            }
//                        });
//                    }
//
//                    function createMemoryChart(summary) {
//                        const ctx = document.getElementById('memoryChart').getContext('2d');
//                        if (charts.memory) charts.memory.destroy();
//
//                        const sorted = Object.entries(summary.endpointStats || {})
//                            .filter(([_, stats]) => stats.avgMemoryKb)
//                            .sort((a, b) => b[1].avgMemoryKb - a[1].avgMemoryKb)
//                            .slice(0, 10);
//
//                        charts.memory = new Chart(ctx, {
//                            type: 'bar',
//                            data: {
//                                labels: sorted.map(([key]) => key.split(' ')[1] || key),
//                                datasets: [{
//                                    label: 'Avg Memory (KB)',
//                                    data: sorted.map(([_, stats]) => stats.avgMemoryKb),
//                                    backgroundColor: 'rgba(249, 115, 22, 0.7)',
//                                    borderColor: 'rgba(249, 115, 22, 1)',
//                                    borderWidth: 2
//                                }]
//                            },
//                            options: {
//                                ...chartDefaults,
//                                scales: {
//                                    y: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } },
//                                    x: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } }
//                                }
//                            }
//                        });
//                    }
//
//                    function createTimelineChart(metrics) {
//                        const ctx = document.getElementById('timelineChart').getContext('2d');
//                        if (charts.timeline) charts.timeline.destroy();
//
//                        const last20 = metrics.slice(-20);
//
//                        charts.timeline = new Chart(ctx, {
//                            type: 'line',
//                            data: {
//                                labels: last20.map((_, i) => `#${i + 1}`),
//                                datasets: [{
//                                    label: 'Response Time (ms)',
//                                    data: last20.map(m => m.executionTimeMs),
//                                    borderColor: 'rgba(99, 102, 241, 1)',
//                                    backgroundColor: 'rgba(99, 102, 241, 0.1)',
//                                    tension: 0.4,
//                                    fill: true
//                                }]
//                            },
//                            options: {
//                                ...chartDefaults,
//                                scales: {
//                                    y: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } },
//                                    x: { ticks: { color: '#cbd5e1' }, grid: { color: '#334155' } }
//                                }
//                            }
//                        });
//                    }
//
//                    function updateTable(data) {
//                        const tbody = document.getElementById('endpointList');
//                        tbody.innerHTML = '';
//
//                        if (!data.endpointStats || Object.keys(data.endpointStats).length === 0) {
//                            tbody.innerHTML = '<tr><td colspan="7" class="py-12 text-center text-slate-400">No data yet</td></tr>';
//                            return;
//                        }
//
//                        Object.entries(data.endpointStats).forEach(([key, stats]) => {
//                            const successRate = stats.totalCalls > 0 ?
//                                ((stats.totalCalls - stats.failedCalls) / stats.totalCalls * 100).toFixed(1) : 0;
//
//                            const row = document.createElement('tr');
//                            row.className = 'border-b border-slate-700/50 hover:bg-slate-700/30 transition-colors';
//                            row.innerHTML = `
//                                <td class="py-3 px-4">
//                                    <span class="px-3 py-1 rounded-lg text-xs font-bold border ${getMethodBadge(stats.method)}">
//                                        ${stats.method}
//                                    </span>
//                                </td>
//                                <td class="py-3 px-4">
//                                    <div class="font-medium text-slate-200">${stats.endpoint}</div>
//                                    ${stats.entityName ? `<div class="text-xs text-slate-400 mt-1">${stats.entityName}</div>` : ''}
//                                </td>
//                                <td class="py-3 px-4 text-center font-semibold">${stats.totalCalls}</td>
//                                <td class="py-3 px-4 text-center">
//                                    <span class="px-2 py-1 rounded-lg text-sm ${
//                                        stats.avgExecutionTimeMs < 50 ? 'bg-green-500/20 text-green-400' :
//                                        stats.avgExecutionTimeMs < 200 ? 'bg-yellow-500/20 text-yellow-400' :
//                                        'bg-red-500/20 text-red-400'
//                                    }">
//                                        ${stats.avgExecutionTimeMs.toFixed(0)}ms
//                                    </span>
//                                </td>
//                                <td class="py-3 px-4 text-center text-slate-300">
//                                    <div class="text-xs">${stats.minExecutionTimeMs || 0}ms / ${stats.maxExecutionTimeMs}ms</div>
//                                </td>
//                                <td class="py-3 px-4 text-center">
//                                    ${stats.avgMemoryKb ?
//                                        `<span class="px-2 py-1 rounded-lg text-sm border ${getMemoryBadge(stats.avgMemoryKb)}">${formatMemory(stats.avgMemoryKb)}</span>` :
//                                        '<span class="text-slate-500">N/A</span>'
//                                    }
//                                </td>
//                                <td class="py-3 px-4 text-center">
//                                    <div class="flex items-center justify-center gap-2">
//                                        <span class="text-sm font-semibold ${successRate >= 95 ? 'text-green-400' : successRate >= 80 ? 'text-yellow-400' : 'text-red-400'}">
//                                            ${successRate}%%
//                                        </span>
//                                        ${stats.failedCalls > 0 ?
//                                            `<span class="px-2 py-0.5 bg-red-500/20 text-red-400 rounded text-xs">${stats.failedCalls} failed</span>` :
//                                            '<span class="text-green-400 text-xs">✓</span>'
//                                        }
//                                    </div>
//                                </td>
//                            `;
//                            tbody.appendChild(row);
//                        });
//                    }
//
//                    async function clearMetrics() {
//                        if (!confirm('Clear all performance metrics? This cannot be undone.')) return;
//                        try {
//                            const response = await fetch(`${API_BASE}/metrics`, { method: 'DELETE' });
//                            const result = await response.json();
//                            if (result.success) {
//                                Object.values(charts).forEach(chart => chart && chart.destroy());
//                                charts = {};
//                                loadData();
//                            } else {
//                                showError('Failed to clear metrics');
//                            }
//                        } catch (error) {
//                            showError('Error: ' + error.message);
//                        }
//                    }
//
//                    function toggleAutoRefresh() {
//                        autoRefresh = !autoRefresh;
//                        const btn = document.getElementById('autoRefreshText');
//                        if (autoRefresh) {
//                            btn.textContent = '⏸ Pause Auto-Refresh';
//                            startAutoRefresh();
//                        } else {
//                            btn.textContent = '▶ Resume Auto-Refresh';
//                            stopAutoRefresh();
//                        }
//                    }
//
//                    function startAutoRefresh() {
//                        if (refreshInterval) clearInterval(refreshInterval);
//                        refreshInterval = setInterval(loadData, 5000);
//                    }
//
//                    function stopAutoRefresh() {
//                        if (refreshInterval) {
//                            clearInterval(refreshInterval);
//                            refreshInterval = null;
//                        }
//                    }
//
//                    function showError(message) {
//                        document.getElementById('errorText').textContent = message;
//                        document.getElementById('error').classList.remove('hidden');
//                        document.getElementById('loading').classList.add('hidden');
//                    }
//
//                    loadData();
//                    startAutoRefresh();
//                </script>
//            </body>
//            </html>
//            """.formatted(apiBase);
//    }