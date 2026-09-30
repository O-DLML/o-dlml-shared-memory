public final class StrategyFactory {
    private StrategyFactory() {}

    public static LoadBalancingStrategy create(StrategyType t) {
        switch (t) {
            case ROUND_ROBIN:   return new RoundRobinStrategy();
            case WORK_STEALING: return new WorkStealingStrategy();
            case AUCTION:
            default:            return new AuctionStrategy();
        }
    }
}
