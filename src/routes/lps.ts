import { Router } from 'express'
import { db } from '../db/client'
import { lps } from 'pe-sub-db/src/schema'
import { eq, and, ilike } from 'drizzle-orm'

export const lpsRouter = Router()

lpsRouter.get('/', async (req, res, next) => {
  try {
    const { facilityId, cls, search } = req.query as Record<string, string>
    const conditions = []
    if (facilityId) conditions.push(eq(lps.facilityId, Number(facilityId)))
    if (cls)        conditions.push(eq(lps.cls, cls))
    if (search)     conditions.push(ilike(lps.name, `%${search}%`))

    const rows = await db.select().from(lps)
      .where(conditions.length ? and(...conditions) : undefined)
      .orderBy(lps.rank)
    res.json(rows)
  } catch (err) { next(err) }
})

lpsRouter.get('/:id', async (req, res, next) => {
  try {
    const [row] = await db.select().from(lps).where(eq(lps.id, Number(req.params.id)))
    if (!row) { res.status(404).json({ error: 'Not found' }); return }
    res.json(row)
  } catch (err) { next(err) }
})

lpsRouter.patch('/:id', async (req, res, next) => {
  try {
    const [updated] = await db
      .update(lps)
      .set({ ...req.body, updatedAt: new Date() })
      .where(eq(lps.id, Number(req.params.id)))
      .returning()
    res.json(updated)
  } catch (err) { next(err) }
})
