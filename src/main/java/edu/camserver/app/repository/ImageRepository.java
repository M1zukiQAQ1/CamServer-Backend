package edu.camserver.app.repository;

import edu.camserver.app.model.Image;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface ImageRepository extends JpaRepository<Image, Long>, QuerydslPredicateExecutor<Image> {

}
