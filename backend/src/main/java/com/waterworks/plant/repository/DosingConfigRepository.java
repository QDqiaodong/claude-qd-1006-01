package com.waterworks.plant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.waterworks.plant.entity.DosingConfig;

public interface DosingConfigRepository extends JpaRepository<DosingConfig, Long> {

    Optional<DosingConfig> findByConfigTypeAndConfigValue(String configType, String configValue);

    List<DosingConfig> findByConfigTypeAndActiveTrue(String configType);

    List<DosingConfig> findAllByOrderByConfigTypeAscIdAsc();
}
