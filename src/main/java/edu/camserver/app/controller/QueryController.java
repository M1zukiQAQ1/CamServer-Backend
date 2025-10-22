package edu.camserver.app.controller;

import edu.camserver.app.service.DatabaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QueryController {

    @Autowired
    private DatabaseService db;

    @GetMapping("/query")
    public List<Map<String,Object>> query(
            @RequestParam(defaultValue="20") int pagesize,
            @RequestParam(required=false) String conditions,
            @RequestParam(defaultValue="DESC") String order,
            @RequestParam(required=false) String lastUID) {

        return db.queryImages(pagesize, conditions, order, lastUID);
    }

    @GetMapping("/sites")
    public List<Map<String,Object>> sites() {
        return db.querySites();
    }
}