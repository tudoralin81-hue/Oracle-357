package ro.alintudor.oracle.core

import kotlin.math.abs

object OracleAlertEngine {
    fun generate(positions: List<OraclePosition>, actions: List<OracleAction>): List<OracleAlert> {
        val now = System.currentTimeMillis()
        val byTicker = actions.associateBy { it.ticker }
        return positions.flatMap { p ->
            val a = byTicker[p.ticker]
            buildList {
                if (p.pnlPercent <= -10.0) add(OracleAlert(p.ticker,"HIGH","Significant loss","P/L below -10%",now,true))
                if (p.weight >= 35.0) add(OracleAlert(p.ticker,"HIGH","High concentration","Weight above 35%",now,true))
                if (a != null && abs(a.score) >= 70.0) add(OracleAlert(p.ticker,if(a.action=="SELL")"HIGH" else "MEDIUM","Oracle signal ${a.action}",a.reason,now,true))
            }
        }
    }
}
