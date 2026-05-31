package com.ubs.pesubapi.controller;

import com.ubs.pesubapi.entity.Lp;
import com.ubs.pesubapi.repository.LpRepository;
import com.ubs.pesubapi.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lps")
public class LpController {

    private final LpRepository        repo;
    private final NotificationService notifier;

    public LpController(LpRepository repo, NotificationService notifier) {
        this.repo     = repo;
        this.notifier = notifier;
    }

    @GetMapping
    public List<Lp> list(@RequestParam(required = false) Integer facilityId,
                          @RequestParam(required = false) String cls,
                          @RequestParam(required = false) String search) {
        if (facilityId != null && cls != null) {
            return repo.findByFacilityIdAndClsOrderByRankAsc(facilityId, cls);
        }
        if (facilityId != null && search != null) {
            return repo.findByFacilityIdAndNameContainingIgnoreCaseOrderByRankAsc(facilityId, search);
        }
        if (facilityId != null) {
            return repo.findByFacilityIdOrderByRankAsc(facilityId);
        }
        return repo.findAllByOrderByRankAsc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Lp> get(@PathVariable int id) {
        return repo.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Lp> patch(@PathVariable int id,
                                     @RequestBody Map<String, Object> body) {
        return repo.findById(id).map(lp -> {
            String prevCls = lp.getCls();
            if (body.containsKey("cls"))    lp.setCls((String) body.get("cls"));
            if (body.containsKey("clsTag")) lp.setClsTag((String) body.get("clsTag"));
            if (body.containsKey("abb"))    lp.setAbb((String) body.get("abb"));
            if (body.containsKey("inc"))    lp.setInc((Boolean) body.get("inc"));
            if (body.containsKey("rcl"))    lp.setRcl((Boolean) body.get("rcl"));
            if (body.containsKey("notes"))  lp.setNotes((String) body.get("notes"));
            lp.setUpdatedAt(LocalDateTime.now());
            Lp saved = repo.save(lp);
            if (body.containsKey("cls") && !lp.getCls().equals(prevCls)) {
                notifier.broadcast(lp.getName() + " reclassified to " + lp.getCls());
            }
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }
}
