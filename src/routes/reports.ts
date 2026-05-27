import { Router } from 'express'
import { db } from '../db/client'
import { bbSnapshots, facilities } from 'pe-sub-db/src/schema'
import { eq, desc } from 'drizzle-orm'
import type { BBResult } from 'pe-sub-common'

export const reportsRouter = Router()

// GET /api/reports/collateral/:facilityId
reportsRouter.get('/collateral/:facilityId', async (req, res, next) => {
  try {
    const [snapshot] = await db.select().from(bbSnapshots)
      .where(eq(bbSnapshots.facilityId, Number(req.params.facilityId)))
      .orderBy(desc(bbSnapshots.calculatedAt))
      .limit(1)
    if (!snapshot) { res.status(404).json({ error: 'No snapshot found' }); return }
    const result = snapshot.result as BBResult
    res.json({ facilityId: snapshot.facilityId, calculatedAt: snapshot.calculatedAt, summary: result.summary })
  } catch (err) { next(err) }
})

// GET /api/reports/concentration/:facilityId
reportsRouter.get('/concentration/:facilityId', async (req, res, next) => {
  try {
    const [snapshot] = await db.select().from(bbSnapshots)
      .where(eq(bbSnapshots.facilityId, Number(req.params.facilityId)))
      .orderBy(desc(bbSnapshots.calculatedAt))
      .limit(1)
    if (!snapshot) { res.status(404).json({ error: 'No snapshot found' }); return }
    const result = snapshot.result as BBResult
    res.json({ breaches: result.breaches })
  } catch (err) { next(err) }
})
