package dev.glassbook.server.projection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CursorRepository extends JpaRepository<ProjectionCursor, Integer> { }
