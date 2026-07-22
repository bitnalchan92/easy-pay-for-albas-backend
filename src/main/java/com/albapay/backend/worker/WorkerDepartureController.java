package com.albapay.backend.worker;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/workers")
@RequiredArgsConstructor
public class WorkerDepartureController {
    private final WorkerDepartureService service;

    @PostMapping("/{workerId}/departure")
    public Map<String, Object> depart(@PathVariable String workerId, @RequestBody Map<String, Object> body) {
        return Map.of("ok", true, "reason", service.depart(workerId, DepartureRequest.from(body)));
    }
}
