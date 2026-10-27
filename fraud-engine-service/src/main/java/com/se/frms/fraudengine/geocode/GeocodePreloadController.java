package com.se.frms.fraudengine.geocode;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints to fill the location cache ahead of time (see GeocodePreloadService).
 * Internal use only: call fraud-engine directly (not through the API gateway).
 *
 *   POST /api/v1/geocode/preload         body: [{"latitude":19.076,"longitude":72.877}, ...]
 *   GET  /api/v1/geocode/preload/status
 */
@RestController
@RequestMapping("/api/v1/geocode/preload")
@RequiredArgsConstructor
public class GeocodePreloadController {

    private static final int MAX_COORDINATES = 50_000;

    private final GeocodePreloadService preloadService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody List<GeocodePreloadService.Coordinate> coordinates) {
        if (coordinates == null || coordinates.isEmpty() || coordinates.size() > MAX_COORDINATES) {
            return ResponseEntity.badRequest().body(Map.of("error", "Send 1 to " + MAX_COORDINATES + " coordinates"));
        }
        List<GeocodePreloadService.Coordinate> valid = coordinates.stream()
                .filter(c -> c != null && c.latitude() != null && c.longitude() != null)
                .toList();
        if (!preloadService.start(valid)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A preload is already running"));
        }
        return ResponseEntity.accepted().body(Map.of("accepted", valid.size(), "skippedInvalid", coordinates.size() - valid.size()));
    }

    @GetMapping("/status")
    public ResponseEntity<GeocodePreloadService.Status> status() {
        return ResponseEntity.ok(preloadService.status());
    }
}
