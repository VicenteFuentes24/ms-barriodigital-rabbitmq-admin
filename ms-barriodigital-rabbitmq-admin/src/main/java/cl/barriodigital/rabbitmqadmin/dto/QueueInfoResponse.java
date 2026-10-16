package cl.barriodigital.rabbitmqadmin.dto;

public record QueueInfoResponse(String name, int messageCount, int consumerCount) { }
