package com.hospital.planner.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "asset_aliases")
public class AssetAlias {
    @Id @Column(nullable = false) public String alias;
    @ManyToOne(optional = false) @JoinColumn(name = "asset_id") public Equipment equipment;
}
