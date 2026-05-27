import { Router } from 'express'
import { db } from '../db/client'
import { lps, facilities, bbSnapshots } from 'pe-sub-db/src/schema'
import { eq } from 'drizzle-orm'
import { computePortfolioBB } from 'pe-sub-common'
import type { LP } from 'pe-sub-common'

export const bbRouter = Router()

// POST /api/bb/run/:facilityId — compute shadow BB and persist snapshot
bbRouter.post('/run/:facilityId', async (req, res, next) => {
  try {
    const facilityId = Number(req.params.facilityId)
    const [facility] = await db.select().from(facilities).where(eq(facilities.id, facilityId))
    if (!facility) { res.status(404).json({ error: 'Facility not found' }); return }

    const lpRows = await db.select().from(lps).where(eq(lps.facilityId, facilityId))
    const result = computePortfolioBB(lpRows as unknown as LP[], {
      concLimitM: Number(facility.concLimitM),
    })

    const [snapshot] = await db.insert(bbSnapshots).values({
      facilityId,
      result,
    }).returning()

    await db.update(facilities)
      .set({ lastRunAt: new Date(), updatedAt: new Date() })
      .where(eq(facilities.id, facilityId))

    res.status(201).json(snapshot)
  } catch (err) { next(err) }
})

// GET /api/bb/snapshots/:facilityId — latest snapshot for a facility
bbRouter.get('/snapshots/:facilityId', async (req, res, next) => {
  try {
    const rows = await db.select().from(bbSnapshots)
      .where(eq(bbSnapshots.facilityId, Number(req.params.facilityId)))
      .orderBy(bbSnapshots.calculatedAt)
    res.json(rows)
  } catch (err) { next(err) }
})
