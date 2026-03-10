package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.ProcessDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, Long> {

    /** Returns the latest version of a definition by name, or empty if none exists. */
    Optional<ProcessDefinition> findTopByNameOrderByVersionDesc(String name);

    /** Returns a specific version of a definition. */
    Optional<ProcessDefinition> findByNameAndVersion(String name, int version);

    /** Returns true if any version of a definition with this name exists. */
    boolean existsByName(String name);

    /** Returns the latest version of every distinct definition name. */
    @Query("SELECT d FROM ProcessDefinition d WHERE d.version = " +
            "(SELECT MAX(d2.version) FROM ProcessDefinition d2 WHERE d2.name = d.name) " +
            "ORDER BY d.name")
    List<ProcessDefinition> findLatestVersionOfAll();

    /** Deletes all versions of a definition by name. */
    @Modifying
    @Query("DELETE FROM ProcessDefinition d WHERE d.name = :name")
    void deleteAllByName(@Param("name") String name);
}
