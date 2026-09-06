package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Data @NoArgsConstructor @Entity @Table(name="managed_view_version")
public class ManagedViewVersion {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,updatable=false) private Long viewId;
 @Column(nullable=false,updatable=false) private Integer versionNo;
 @Column(nullable=false,updatable=false,columnDefinition="LONGTEXT") private String sqlContent;
 @Column(nullable=false,updatable=false,columnDefinition="JSON") private String dependenciesJson;
 @Column(nullable=false,updatable=false,columnDefinition="JSON") private String columnsJson;
 @Column(columnDefinition="LONGTEXT") private String engineDefinition;
 @Column(nullable=false,length=16) private String status;
 @Column(nullable=false,updatable=false) private Long createdBy;
 @Column(nullable=false,updatable=false) private LocalDateTime createdAt;
}
