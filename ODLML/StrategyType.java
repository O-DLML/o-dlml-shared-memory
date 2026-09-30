public enum StrategyType {
    AUCTION, ROUND_ROBIN, WORK_STEALING;

    public static StrategyType fromString(String s) {
        if (s == null) return AUCTION;
        switch (s.trim().toLowerCase()) {
            case "auction":       
                return AUCTION;
            case "roundrobin":
            case "round_robin":   
                return ROUND_ROBIN;
            case "workstealing":
            case "work_stealing": 
                return WORK_STEALING;
            default:              
                return AUCTION;
        }
    }
}
