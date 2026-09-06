package com.rtdwh.entity;
import jakarta.persistence.*;
import lombok.*;
@Data @NoArgsConstructor @Entity @Table(name="managed_view")
public class ManagedView {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true) private Long tableMetaId;
 @Column(nullable=false,columnDefinition="LONGTEXT") private String draftSql;
 private Long publishedVersionId;
 private Long pendingVersionId;
 @Column(nullable=false,length=16) private String operationState = "idle";
 @Column(columnDefinition="TEXT") private String lastError;
 @Version private Long version;
}
