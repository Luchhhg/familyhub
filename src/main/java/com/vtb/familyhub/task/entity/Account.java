package com.vtb.familyhub.task.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    @Id
    private Long userId; // ID ребенка или родителя

    @Column(nullable = false)
    private BigDecimal balance;
}