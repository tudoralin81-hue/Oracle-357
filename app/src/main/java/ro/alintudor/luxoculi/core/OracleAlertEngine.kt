package ro.alintudor.luxoculi.core

import kotlin.math.abs

object OracleAlertEngine {
    fun generate(positions: List<OraclePosition>, actions: List<OracleAction>): List<OracleAlert> {
        val now = System.currentTimeMillis()
        val byTicker = actions.associateBy { it.ticker }
        return positions.flatMap { p ->
            val a = byTicker[p.ticker]
            buildList {
                if (p.pnlPercent <= -10.0) add(OracleAlert(p.ticker,"HIGH","Significant loss","P/L below -10%",now,true))
                val bar = if (positions.size in 1..3) 50.0 else 35.0
                if (p.weight >= bar) add(OracleAlert(p.ticker,"HIGH","High concentration","Weight above ${bar.toInt()}%",now,true))
                if (a != null && (abs(a.score) >= 70.0 || a.action == "REDUCE")) add(OracleAlert(p.ticker,if(a.action=="SELL")"HIGH" else "MEDIUM","Lux Oculi signal ${a.action}",a.reason,now,true))
            }
        }
    }
}
